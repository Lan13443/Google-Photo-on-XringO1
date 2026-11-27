#define _GNU_SOURCE
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define LOG_TAG "GallerySrgbFix"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Android ui::Dataspace / ADataSpace constants */
#define DS_DISPLAY_P3        ((int32_t)143261696) /* 0x088a0000 */
#define DS_DISPLAY_P3_LINEAR ((int32_t)141164544) /* 0x086a0000 */
#define DS_DCI_P3            ((int32_t)155844608) /* 0x094a0000 */
#define DS_V0_SRGB           ((int32_t)142671872) /* 0x08810000 */

/* EGL colorspace constants */
#define EGL_NONE_VAL                  0x3038
#define EGL_GL_COLORSPACE_KHR_KEY     0x309D
#define EGL_GL_COLORSPACE_SRGB_KHR    0x3089
#define EGL_GL_COLORSPACE_P3_LINEAR   0x3362
#define EGL_GL_COLORSPACE_P3_EXT      0x3363
#define EGL_GL_COLORSPACE_P3_PASSTHRU 0x3490

/* Vulkan VkColorSpaceKHR constants */
#define VK_COLOR_SPACE_SRGB_NONLINEAR_KHR       0
#define VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT 1000104001
#define VK_COLOR_SPACE_DISPLAY_P3_LINEAR_EXT    1000104003
#define VK_COLOR_SPACE_DCI_P3_NONLINEAR_EXT     1000104004

/* AArch64 relocation types */
#ifndef R_AARCH64_ABS64
#define R_AARCH64_ABS64 257
#endif
#ifndef R_AARCH64_GLOB_DAT
#define R_AARCH64_GLOB_DAT 1025
#endif
#ifndef R_AARCH64_JUMP_SLOT
#define R_AARCH64_JUMP_SLOT 1026
#endif

typedef struct VkSwapchainCreateInfoKHR_ABI {
    uint32_t sType;
    const void *pNext;
    uint32_t flags;
    uint64_t surface;
    uint32_t minImageCount;
    uint32_t imageFormat;
    int32_t imageColorSpace;
    uint32_t width;
    uint32_t height;
    uint32_t imageArrayLayers;
    uint32_t imageUsage;
    uint32_t imageSharingMode;
    uint32_t queueFamilyIndexCount;
    const uint32_t *pQueueFamilyIndices;
    uint32_t preTransform;
    uint32_t compositeAlpha;
    uint32_t presentMode;
    uint32_t clipped;
    uint64_t oldSwapchain;
} VkSwapchainCreateInfoKHR_ABI;

_Static_assert(offsetof(VkSwapchainCreateInfoKHR_ABI, imageColorSpace) == 40, "imageColorSpace offset");
_Static_assert(sizeof(VkSwapchainCreateInfoKHR_ABI) == 104, "VkSwapchainCreateInfoKHR size");

/* Forward declaration */
static void on_hyos_child_fork(void);

/* Official Zygisk Next Native Module ABI */
struct ZygiskNextModule {
    int target_api_version;
    void (*onModuleLoaded)(void *self_handle, const void *api);
};
_Static_assert(sizeof(struct ZygiskNextModule) == 16, "ZygiskNextModule size");

static int g_is_hyos = 0;
static int g_hooks_preloaded_done = 0;
static int g_hooks_app_done = 0;
static int g_log_budget = 60;
static pthread_mutex_t g_patch_mutex = PTHREAD_MUTEX_INITIALIZER;

/* Child gate slots for hyos_spawner dlopen and libhyper_os_shell android_dlopen_ext */
static void *(*orig_hyos_dlopen)(const char *filename, int flags) = NULL;
static uintptr_t g_hyos_dlopen_slot = 0;

typedef void *(*fn_android_dlopen_ext_t)(const char *filename, int flag, const void *extinfo);
static fn_android_dlopen_ext_t orig_shell_dlopen_ext = NULL;
static uintptr_t g_shell_dlopen_ext_slot = 0;

/* Original function pointers for hooked graphics / dataspace APIs */
typedef void *(*fn_set_dataspace_t)(void *self, const void *sc, int32_t ds);
typedef void *(*fn_dlsym_t)(void *handle, const char *symbol);
typedef void *(*fn_egl_create_win_t)(void *dpy, void *config, void *win, const int32_t *attrib_list);
typedef int32_t (*fn_nw_set_ds_t)(void *window, int32_t dataSpace);
typedef void (*fn_ast_set_ds_t)(void *transaction, void *surface_control, int32_t data_space);
typedef void *(*fn_vk_get_instance_proc_t)(void *instance, const char *pName);
typedef void *(*fn_vk_get_device_proc_t)(void *device, const char *pName);
typedef int32_t (*fn_vk_create_swapchain_t)(void *device, const VkSwapchainCreateInfoKHR_ABI *pCreateInfo, const void *pAllocator, uint64_t *pSwapchain);

static fn_set_dataspace_t orig_set_dataspace = NULL;
static fn_dlsym_t orig_dlsym = NULL;
static fn_egl_create_win_t orig_egl_create_window_surface = NULL;
static fn_nw_set_ds_t orig_nw_set_ds = NULL;
static fn_ast_set_ds_t orig_ast_set_ds = NULL;
static fn_vk_get_instance_proc_t orig_vk_get_instance_proc_addr = NULL;
static fn_vk_get_device_proc_t orig_vk_get_device_proc_addr = NULL;
static fn_vk_create_swapchain_t orig_vk_create_swapchain = NULL;

static inline int32_t clamp_p3_dataspace(int32_t ds) {
    if (ds == DS_DISPLAY_P3 || ds == DS_DISPLAY_P3_LINEAR || ds == DS_DCI_P3) {
        return DS_V0_SRGB;
    }
    return ds;
}

static inline int32_t clamp_vk_colorspace(int32_t cs) {
    if (cs == VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT ||
        cs == VK_COLOR_SPACE_DISPLAY_P3_LINEAR_EXT ||
        cs == VK_COLOR_SPACE_DCI_P3_NONLINEAR_EXT) {
        return VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    }
    return cs;
}

static inline int32_t clamp_egl_colorspace(int32_t cs) {
    if (cs == EGL_GL_COLORSPACE_P3_EXT ||
        cs == EGL_GL_COLORSPACE_P3_LINEAR ||
        cs == EGL_GL_COLORSPACE_P3_PASSTHRU) {
        return EGL_GL_COLORSPACE_SRGB_KHR;
    }
    return cs;
}

static void read_self_cmdline(char *buf, size_t cap) {
    buf[0] = '\0';
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    ssize_t n = read(fd, buf, cap - 1);
    close(fd);
    if (n > 0) {
        buf[n] = '\0';
    }
}

/* Hooked graphics / dataspace functions */
static void *hook_set_dataspace(void *self, const void *sc, int32_t ds) {
    int32_t clamped = clamp_p3_dataspace(ds);
    if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) {
        if (clamped != ds) {
            LOGI("Transaction::setDataspace CLAMPED %d -> %d", ds, clamped);
        } else {
            LOGI("Transaction::setDataspace passthrough ds=%d", ds);
        }
    }
    return orig_set_dataspace(self, sc, clamped);
}

static int32_t hook_nw_set_ds(void *window, int32_t dataSpace) {
    int32_t clamped = clamp_p3_dataspace(dataSpace);
    if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) {
        if (clamped != dataSpace) {
            LOGI("ANativeWindow_setBuffersDataSpace CLAMPED %d -> %d", dataSpace, clamped);
        } else {
            LOGI("ANativeWindow_setBuffersDataSpace passthrough ds=%d", dataSpace);
        }
    }
    return orig_nw_set_ds(window, clamped);
}

static void hook_ast_set_ds(void *transaction, void *surface_control, int32_t data_space) {
    int32_t clamped = clamp_p3_dataspace(data_space);
    if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) {
        if (clamped != data_space) {
            LOGI("ASurfaceTransaction_setBufferDataSpace CLAMPED %d -> %d", data_space, clamped);
        } else {
            LOGI("ASurfaceTransaction_setBufferDataSpace passthrough ds=%d", data_space);
        }
    }
    orig_ast_set_ds(transaction, surface_control, clamped);
}

static int32_t hook_vk_create_swapchain(
    void *device,
    const VkSwapchainCreateInfoKHR_ABI *pCreateInfo,
    const void *pAllocator,
    uint64_t *pSwapchain
) {
    if (pCreateInfo != NULL) {
        int32_t orig_cs = pCreateInfo->imageColorSpace;
        int32_t clamped = clamp_vk_colorspace(orig_cs);
        if (clamped != orig_cs) {
            VkSwapchainCreateInfoKHR_ABI copy = *pCreateInfo;
            copy.imageColorSpace = clamped;
            if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) {
                LOGI("vkCreateSwapchainKHR CLAMPED colorSpace %d -> %d", orig_cs, clamped);
            }
            return orig_vk_create_swapchain(device, &copy, pAllocator, pSwapchain);
        }
        if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) {
            LOGI("vkCreateSwapchainKHR passthrough colorSpace=%d format=%u", orig_cs, pCreateInfo->imageFormat);
        }
    }
    return orig_vk_create_swapchain(device, pCreateInfo, pAllocator, pSwapchain);
}

static void *hook_vk_get_device_proc_addr(void *device, const char *pName) {
    void *res = orig_vk_get_device_proc_addr(device, pName);
    if (res != NULL && pName != NULL && strcmp(pName, "vkCreateSwapchainKHR") == 0) {
        if (orig_vk_create_swapchain == NULL) {
            orig_vk_create_swapchain = (fn_vk_create_swapchain_t)res;
        }
        if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) {
            LOGI("vkGetDeviceProcAddr hit: %s", pName);
        }
        return (void *)&hook_vk_create_swapchain;
    }
    return res;
}

static void *hook_vk_get_instance_proc_addr(void *instance, const char *pName) {
    void *res = orig_vk_get_instance_proc_addr(instance, pName);
    if (res == NULL || pName == NULL) return res;
    if (strcmp(pName, "vkGetDeviceProcAddr") == 0) {
        if (orig_vk_get_device_proc_addr == NULL) {
            orig_vk_get_device_proc_addr = (fn_vk_get_device_proc_t)res;
        }
        if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) {
            LOGI("vkGetInstanceProcAddr hit: %s", pName);
        }
        return (void *)&hook_vk_get_device_proc_addr;
    }
    if (strcmp(pName, "vkCreateSwapchainKHR") == 0) {
        if (orig_vk_create_swapchain == NULL) {
            orig_vk_create_swapchain = (fn_vk_create_swapchain_t)res;
        }
        if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) {
            LOGI("vkGetInstanceProcAddr hit: %s", pName);
        }
        return (void *)&hook_vk_create_swapchain;
    }
    return res;
}

static void *hook_dlsym(void *handle, const char *symbol) {
    fn_dlsym_t real_dlsym = orig_dlsym ? orig_dlsym : dlsym;
    void *res = real_dlsym(handle, symbol);
    if (res == NULL || symbol == NULL) return res;

    if (strcmp(symbol, "ANativeWindow_setBuffersDataSpace") == 0) {
        if (orig_nw_set_ds == NULL) orig_nw_set_ds = (fn_nw_set_ds_t)res;
        if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) LOGI("dlsym hit: %s", symbol);
        return (void *)&hook_nw_set_ds;
    }
    if (strcmp(symbol, "ASurfaceTransaction_setBufferDataSpace") == 0) {
        if (orig_ast_set_ds == NULL) orig_ast_set_ds = (fn_ast_set_ds_t)res;
        if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) LOGI("dlsym hit: %s", symbol);
        return (void *)&hook_ast_set_ds;
    }
    if (strcmp(symbol, "vkGetInstanceProcAddr") == 0) {
        if (orig_vk_get_instance_proc_addr == NULL) {
            orig_vk_get_instance_proc_addr = (fn_vk_get_instance_proc_t)res;
        }
        if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) LOGI("dlsym hit: %s", symbol);
        return (void *)&hook_vk_get_instance_proc_addr;
    }
    if (strcmp(symbol, "vkGetDeviceProcAddr") == 0) {
        if (orig_vk_get_device_proc_addr == NULL) {
            orig_vk_get_device_proc_addr = (fn_vk_get_device_proc_t)res;
        }
        if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) LOGI("dlsym hit: %s", symbol);
        return (void *)&hook_vk_get_device_proc_addr;
    }
    if (strcmp(symbol, "vkCreateSwapchainKHR") == 0) {
        if (orig_vk_create_swapchain == NULL) {
            orig_vk_create_swapchain = (fn_vk_create_swapchain_t)res;
        }
        if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) LOGI("dlsym hit: %s", symbol);
        return (void *)&hook_vk_create_swapchain;
    }
    return res;
}

static void *hook_egl_create_window_surface(void *dpy, void *config, void *win, const int32_t *attrib_list) {
    int32_t seen_cs = -1;
    if (attrib_list != NULL) {
        int32_t copy[129];
        int changed = 0;
        size_t i = 0;
        while (i < 126) {
            int32_t key = attrib_list[i];
            copy[i] = key;
            if (key == EGL_NONE_VAL) {
                break;
            }
            int32_t val = attrib_list[i + 1];
            if (key == EGL_GL_COLORSPACE_KHR_KEY) {
                seen_cs = val;
                int32_t clamped = clamp_egl_colorspace(val);
                if (clamped != val) {
                    changed = 1;
                    if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) {
                        LOGI("eglCreateWindowSurface CLAMPED colorspace 0x%x -> 0x%x", val, clamped);
                    }
                    val = clamped;
                }
            }
            copy[i + 1] = val;
            i += 2;
        }
        if (changed) {
            copy[i < 128 ? i : 128] = EGL_NONE_VAL;
            return orig_egl_create_window_surface(dpy, config, win, copy);
        }
    }
    if (__sync_fetch_and_sub(&g_log_budget, 1) > 0) {
        LOGI("eglCreateWindowSurface passthrough cs=0x%x", seen_cs);
    }
    return orig_egl_create_window_surface(dpy, config, win, attrib_list);
}

/* Safe AArch64 ELF64 GOT/PLT patcher */
struct patch_req {
    const char *target_substr; /* NULL => main binary (hyos_spawner) */
    const char *sym_name;
    void *replacement;
    void **orig_out;
    uintptr_t *slot_out;
    int patched_count;
};

static inline uintptr_t resolve_dyn_ptr(uintptr_t base, Elf64_Addr ptr) {
    if (ptr == 0) return 0;
    return (ptr < base) ? (base + ptr) : (uintptr_t)ptr;
}

static int write_got_slot(uintptr_t slot_addr, void *replacement, void **orig_out, uintptr_t *slot_out) {
    long ps_l = sysconf(_SC_PAGESIZE);
    size_t ps = (ps_l > 0) ? (size_t)ps_l : 16384u;
    uintptr_t page = slot_addr & ~(ps - 1);

    if (mprotect((void *)page, ps, PROT_READ | PROT_WRITE) != 0) {
        return 0;
    }
    void **slot = (void **)slot_addr;
    if (orig_out != NULL && *orig_out == NULL) {
        *orig_out = *slot;
    }
    if (slot_out != NULL && *slot_out == 0) {
        *slot_out = slot_addr;
    }
    *slot = replacement;
    __sync_synchronize();
    mprotect((void *)page, ps, PROT_READ);
    return 1;
}

static void restore_got_slot(uintptr_t slot_addr, void *orig) {
    if (slot_addr == 0 || orig == NULL) return;
    pthread_mutex_lock(&g_patch_mutex);
    long ps_l = sysconf(_SC_PAGESIZE);
    size_t ps = (ps_l > 0) ? (size_t)ps_l : 16384u;
    uintptr_t page = slot_addr & ~(ps - 1);
    if (mprotect((void *)page, ps, PROT_READ | PROT_WRITE) == 0) {
        void **slot = (void **)slot_addr;
        *slot = orig;
        __sync_synchronize();
        mprotect((void *)page, ps, PROT_READ);
    }
    pthread_mutex_unlock(&g_patch_mutex);
}

static int scan_rela_table(
    uintptr_t base,
    const Elf64_Rela *rela,
    size_t bytes,
    const Elf64_Sym *symtab,
    const char *strtab,
    struct patch_req *req
) {
    if (!rela || !bytes || !symtab || !strtab) return 0;
    size_t count = bytes / sizeof(Elf64_Rela);
    int hits = 0;
    for (size_t i = 0; i < count; i++) {
        uint32_t type = ELF64_R_TYPE(rela[i].r_info);
        if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT && type != R_AARCH64_ABS64) {
            continue;
        }
        uint32_t sym_idx = ELF64_R_SYM(rela[i].r_info);
        if (sym_idx == 0) continue;
        const char *name = strtab + symtab[sym_idx].st_name;
        if (name != NULL && strcmp(name, req->sym_name) == 0) {
            uintptr_t slot_addr = base + (uintptr_t)rela[i].r_offset;
            if (write_got_slot(slot_addr, req->replacement, req->orig_out, req->slot_out)) {
                hits++;
            }
        }
    }
    return hits;
}

static int dl_phdr_patch_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    struct patch_req *req = (struct patch_req *)data;

    if (req->target_substr == NULL) {
        bool is_main = (info->dlpi_name == NULL || info->dlpi_name[0] == '\0' ||
                        strstr(info->dlpi_name, "hyos_spawner") != NULL);
        if (!is_main) return 0;
    } else {
        if (info->dlpi_name == NULL || strstr(info->dlpi_name, req->target_substr) == NULL) {
            return 0;
        }
    }

    uintptr_t base = (uintptr_t)info->dlpi_addr;
    const Elf64_Dyn *dyn = NULL;
    for (Elf64_Half i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = (const Elf64_Dyn *)(base + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return 0;

    const Elf64_Sym *symtab = NULL;
    const char *strtab = NULL;
    const Elf64_Rela *jmprel = NULL;
    size_t pltrelsz = 0;
    const Elf64_Rela *rela = NULL;
    size_t relasz = 0;

    for (const Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_SYMTAB:
                symtab = (const Elf64_Sym *)resolve_dyn_ptr(base, d->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strtab = (const char *)resolve_dyn_ptr(base, d->d_un.d_ptr);
                break;
            case DT_JMPREL:
                jmprel = (const Elf64_Rela *)resolve_dyn_ptr(base, d->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                pltrelsz = (size_t)d->d_un.d_val;
                break;
            case DT_RELA:
                rela = (const Elf64_Rela *)resolve_dyn_ptr(base, d->d_un.d_ptr);
                break;
            case DT_RELASZ:
                relasz = (size_t)d->d_un.d_val;
                break;
            default:
                break;
        }
    }

    req->patched_count += scan_rela_table(base, jmprel, pltrelsz, symtab, strtab, req);
    req->patched_count += scan_rela_table(base, rela, relasz, symtab, strtab, req);
    return 0;
}

static int patch_elf_symbol(
    const char *target_substr,
    const char *sym_name,
    void *replacement,
    void **orig_out,
    uintptr_t *slot_out
) {
    struct patch_req req = {
        .target_substr = target_substr,
        .sym_name = sym_name,
        .replacement = replacement,
        .orig_out = orig_out,
        .slot_out = slot_out,
        .patched_count = 0,
    };
    pthread_mutex_lock(&g_patch_mutex);
    dl_iterate_phdr(dl_phdr_patch_cb, &req);
    pthread_mutex_unlock(&g_patch_mutex);
    return req.patched_count;
}

static void install_preloaded_hooks(void) {
    if (__sync_bool_compare_and_swap(&g_hooks_preloaded_done, 0, 1)) {
        int c_ds = 0, c_dlsym = 0, c_egl = 0;
        const char *sym_set_ds =
            "_ZN7android21SurfaceComposerClient11Transaction12setDataspaceERKNS_2spINS_14SurfaceControlEEENS_8hardware8graphics6common4V1_29DataspaceE";

        c_ds += patch_elf_symbol("libhyper_os_sys.dylib.so", sym_set_ds, (void *)&hook_set_dataspace, (void **)&orig_set_dataspace, NULL);
        c_ds += patch_elf_symbol("libandroid.so", sym_set_ds, (void *)&hook_set_dataspace, (void **)&orig_set_dataspace, NULL);

        c_dlsym += patch_elf_symbol("libhyper_os_flutter.so", "dlsym", (void *)&hook_dlsym, (void **)&orig_dlsym, NULL);
        c_dlsym += patch_elf_symbol("libhyper_os_sys.dylib.so", "dlsym", (void *)&hook_dlsym, (void **)&orig_dlsym, NULL);

        c_egl += patch_elf_symbol("libhyper_os_flutter.so", "eglCreateWindowSurface", (void *)&hook_egl_create_window_surface, (void **)&orig_egl_create_window_surface, NULL);

        LOGI("Gallery hooks installed (preloaded pid=%d): setDataspace=%d dlsym=%d egl=%d",
             getpid(), c_ds, c_dlsym, c_egl);
    }
}

static void install_app_hooks(void) {
    if (__sync_bool_compare_and_swap(&g_hooks_app_done, 0, 1)) {
        int c_dlsym = 0, c_egl = 0;
        c_dlsym += patch_elf_symbol("libapp_gallery.so", "dlsym", (void *)&hook_dlsym, (void **)&orig_dlsym, NULL);
        c_dlsym += patch_elf_symbol("libHyperSurfaceView.so", "dlsym", (void *)&hook_dlsym, (void **)&orig_dlsym, NULL);
        c_egl += patch_elf_symbol("libapp_gallery.so", "eglCreateWindowSurface", (void *)&hook_egl_create_window_surface, (void **)&orig_egl_create_window_surface, NULL);
        c_egl += patch_elf_symbol("libmivklumos.so", "eglCreateWindowSurface", (void *)&hook_egl_create_window_surface, (void **)&orig_egl_create_window_surface, NULL);

        LOGI("Gallery hooks installed (app libs pid=%d): dlsym=%d egl=%d", getpid(), c_dlsym, c_egl);
    }
}

static bool classify_gallery_child(const char *path, bool *cmd_unset_out, bool *path_is_gallery_out) {
    char cmdline[128];
    read_self_cmdline(cmdline, sizeof(cmdline));

    bool path_is_gallery = (path != NULL &&
        (strstr(path, "com.miui.gallery") != NULL ||
         strstr(path, "libapp_gallery.so") != NULL ||
         strstr(path, "libHyperSurfaceView.so") != NULL ||
         strstr(path, "libmivklumos.so") != NULL));
    bool cmd_is_gallery = (strcmp(cmdline, "com.miui.gallery") == 0);
    bool cmd_unset = (cmdline[0] == '\0' || strcmp(cmdline, "usap64") == 0 || strstr(cmdline, "hyos_spawner") != NULL);

    *cmd_unset_out = cmd_unset;
    *path_is_gallery_out = path_is_gallery;
    return (cmd_is_gallery || path_is_gallery);
}

static void restore_child_gates(void) {
    if (g_shell_dlopen_ext_slot != 0 && orig_shell_dlopen_ext != NULL) {
        restore_got_slot(g_shell_dlopen_ext_slot, (void *)orig_shell_dlopen_ext);
    }
    if (g_hyos_dlopen_slot != 0 && orig_hyos_dlopen != NULL) {
        restore_got_slot(g_hyos_dlopen_slot, (void *)orig_hyos_dlopen);
    }
}

static void *hook_shell_android_dlopen_ext(const char *path, int flags, const void *extinfo) {
    fn_android_dlopen_ext_t real_fn = orig_shell_dlopen_ext;
    bool cmd_unset = false, path_is_gallery = false;

    if (!classify_gallery_child(path, &cmd_unset, &path_is_gallery)) {
        if (cmd_unset) {
            return real_fn(path, flags, extinfo);
        }
        restore_child_gates();
        return real_fn(path, flags, extinfo);
    }

    install_preloaded_hooks();
    void *ret = real_fn(path, flags, extinfo);
    if (ret != NULL && path_is_gallery) {
        install_app_hooks();
    }
    return ret;
}

static void *hook_hyos_dlopen(const char *path, int flags) {
    void *(*real_dlopen)(const char *, int) = orig_hyos_dlopen ? orig_hyos_dlopen : dlopen;
    bool cmd_unset = false, path_is_gallery = false;

    if (!classify_gallery_child(path, &cmd_unset, &path_is_gallery)) {
        if (cmd_unset) {
            return real_dlopen(path, flags);
        }
        restore_child_gates();
        return real_dlopen(path, flags);
    }

    install_preloaded_hooks();
    void *ret = real_dlopen(path, flags);
    if (ret != NULL && path_is_gallery) {
        install_app_hooks();
    }
    return ret;
}

static void on_hyos_child_fork(void) {
    if (!g_is_hyos) return;
    /* Reset state in newly forked child process and arm child dlopen gates */
    g_hooks_preloaded_done = 0;
    g_hooks_app_done = 0;
    orig_hyos_dlopen = NULL;
    g_hyos_dlopen_slot = 0;
    orig_shell_dlopen_ext = NULL;
    g_shell_dlopen_ext_slot = 0;
    patch_elf_symbol("libhyper_os_shell.so", "android_dlopen_ext", (void *)&hook_shell_android_dlopen_ext, (void **)&orig_shell_dlopen_ext, &g_shell_dlopen_ext_slot);
    patch_elf_symbol(NULL, "dlopen", (void *)&hook_hyos_dlopen, (void **)&orig_hyos_dlopen, &g_hyos_dlopen_slot);
}

static void on_zn_module_loaded(void *self_handle, const void *api) {
    (void)self_handle;
    (void)api;
    char exe[256];
    ssize_t len = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (len <= 0) return;
    exe[len] = '\0';
    if (strcmp(exe, "/system_ext/bin/hyos_spawner") != 0) return;
    if (!__sync_bool_compare_and_swap(&g_is_hyos, 0, 1)) return;
    pthread_atfork(NULL, NULL, on_hyos_child_fork);
    LOGI("onModuleLoaded armed in hyos_spawner pid=%d", getpid());
}

__attribute__((visibility("default")))
struct ZygiskNextModule zn_module = {
    .target_api_version = 4,
    .onModuleLoaded = on_zn_module_loaded,
};

/* Diskless guest prerequisite check. Does not open disks or change the host. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <drm/drm.h>
#include <drm/virtgpu_drm.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/stat.h>
#include <unistd.h>

/* Virtio-GPU protocol ID; older Linux UAPI headers expose the context ioctl but
 * do not yet give this capset a symbolic name. It is not a Vulkan API version. */
enum { GFXSTREAM_VULKAN_CAPSET = 3 };

static void finish(int passed) {
    puts(passed ? "DROIDDECK_VIRTGPU_PASS_V1" : "DROIDDECK_VIRTGPU_FAIL_V1");
    fflush(stdout);
    sync();
    reboot(RB_POWER_OFF);
    for (;;) pause();
}

static int parameter(int fd, uint64_t id, uint64_t* value) {
    struct drm_virtgpu_getparam request = {
        .param = id,
        .value = (uint64_t)(uintptr_t)value,
    };
    return ioctl(fd, DRM_IOCTL_VIRTGPU_GETPARAM, &request);
}

int main(void) {
    if (getpid() != 1) {
        fputs("Run only as PID 1 in the diskless probe VM.\n", stderr);
        return 2;
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    puts("DROIDDECK_VIRTGPU_BEGIN_V1");
    if (mount("devtmpfs", "/dev", "devtmpfs", 0, NULL) != 0) {
        perror("mount devtmpfs");
        finish(0);
    }
    mkdir("/proc", 0755);
    if (mount("proc", "/proc", "proc", 0, NULL) != 0) {
        perror("mount proc");
        finish(0);
    }
    char cmdline[4096] = {0};
    FILE* file = fopen("/proc/cmdline", "r");
    if (!file || !fgets(cmdline, sizeof(cmdline), file)) {
        perror("read cmdline");
        finish(0);
    }
    fclose(file);
    int allow_2d = 0;
    char* save = NULL;
    for (char* word = strtok_r(cmdline, " \n", &save); word; word = strtok_r(NULL, " \n", &save))
        if (!strcmp(word, "droiddeck_probe_ci_2d=1")) allow_2d = 1;

    int fd = -1;
    /* virtio-pci's asynchronous probe may finish after PID 1 starts. */
    for (int i = 0; i < 100 && fd < 0; i++) {
        fd = open("/dev/dri/renderD128", O_RDWR | O_CLOEXEC);
        if (fd < 0) usleep(100000);
    }
    if (fd < 0) {
        perror("open renderD128");
        finish(0);
    }
    char name[64] = {0};
    struct drm_version version = {.name_len = sizeof(name) - 1, .name = name};
    if (ioctl(fd, DRM_IOCTL_VERSION, &version) != 0 || strcmp(name, "virtio_gpu")) {
        fprintf(stderr, "Expected virtio_gpu, got %s\n", name);
        finish(0);
    }
    puts("DROIDDECK_VIRTGPU_NODE_V1");
    if (allow_2d) {
        /* CI's QEMU 2D device proves only kernel enumeration, never Gfxstream. */
        puts("DROIDDECK_VIRTGPU_CI_2D_V1");
        close(fd);
        finish(1);
    }
    uint64_t capsets = 0, blobs = 0, contexts = 0;
    if (parameter(fd, VIRTGPU_PARAM_SUPPORTED_CAPSET_IDs, &capsets) != 0 ||
        parameter(fd, VIRTGPU_PARAM_RESOURCE_BLOB, &blobs) != 0 ||
        parameter(fd, VIRTGPU_PARAM_CONTEXT_INIT, &contexts) != 0) {
        perror("virtgpu parameters");
        finish(0);
    }
    printf("capsets=0x%llx resource_blob=%llu context_init=%llu\n", (unsigned long long)capsets,
           (unsigned long long)blobs, (unsigned long long)contexts);
    if (!(capsets & (1ULL << GFXSTREAM_VULKAN_CAPSET)) || !blobs || !contexts)
        finish(0);
    struct drm_virtgpu_context_set_param param = {
        .param = VIRTGPU_CONTEXT_PARAM_CAPSET_ID,
        .value = GFXSTREAM_VULKAN_CAPSET,
    };
    struct drm_virtgpu_context_init init = {
        .num_params = 1,
        .ctx_set_params = (uint64_t)(uintptr_t)&param,
    };
    if (ioctl(fd, DRM_IOCTL_VIRTGPU_CONTEXT_INIT, &init) != 0) {
        perror("Gfxstream context init");
        finish(0);
    }
    puts("DROIDDECK_GFXSTREAM_CONTEXT_V1");
    close(fd);
    finish(1);
    return 0;
}

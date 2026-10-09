#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char memfd_name[] = "droiddeck-module-probe";
static unsigned char bytes[65536];

static int compat_memfd(void)
{
    int result;
    __asm__ volatile("int $0x80" : "=a"(result)
                     : "0"(356), "b"(memfd_name), "c"(0)
                     : "memory", "cc");
    return result;
}

int main(int argc, char **argv)
{
    if (argc != 3) return 2;
    const char *module = argv[1], *mode = argv[2];
    int fd = -1;
    char path[PATH_MAX];
    if (!strcmp(mode, "bad-directory")) {
        int result = compat_memfd();
        if (result != -EACCES) {
            if (result >= 0) close(result);
            fprintf(stderr, "expected -EACCES, got %d\n", result);
            return 3;
        }
        puts("PASS: unsafe fallback directory rejected");
        return 0;
    }
    if (strcmp(mode, "disk")) {
        FILE *source = fopen(module, "rb");
        if (!source) { perror("fopen"); return 4; }
        size_t length = fread(bytes, 1, sizeof(bytes), source);
        if (ferror(source) || !feof(source)) { fclose(source); return 5; }
        fclose(source);
        if (!strcmp(mode, "memfd")) {
            fd = compat_memfd();
            if (fd < 0) { fprintf(stderr, "memfd result %d\n", fd); return 6; }
        } else if (!strcmp(mode, "unlinked")) {
            const char *dir = getenv("TMPDIR");
            if (!dir) dir = "/tmp";
            if (snprintf(path, sizeof(path), "%s/module-XXXXXX", dir) >= (int)sizeof(path)) return 7;
            fd = mkstemp(path);
            if (fd < 0) { perror("mkstemp"); return 8; }
            if (unlink(path)) { perror("unlink"); close(fd); return 9; }
        } else return 10;
        if (write(fd, bytes, length) != (ssize_t)length) { close(fd); return 11; }
        snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        module = path;
    }
    off_t position = fd < 0 ? 0 : lseek(fd, 0, SEEK_CUR);
    void *library = dlopen(module, RTLD_NOW | RTLD_LOCAL);
    if (!library) { fprintf(stderr, "dlopen: %s\n", dlerror()); return 12; }
    int (*probe)(void) = (int (*)(void))dlsym(library, "droiddeck_module_probe");
    if (!probe) { fprintf(stderr, "dlsym: %s\n", dlerror()); return 13; }
    if (probe() != 42) return 14;
    if (fd >= 0 && lseek(fd, 0, SEEK_CUR) != position) return 15;
    if (dlclose(library)) return 16;
    if (fd >= 0) close(fd);
    printf("PASS: module load and symbol call through %s\n", mode);
    return 0;
}

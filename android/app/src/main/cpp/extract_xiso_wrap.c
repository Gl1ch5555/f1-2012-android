#define __LINUX__ 1
#define _LARGEFILE64_SOURCE 1

#include <setjmp.h>
#include <unistd.h>

static jmp_buf g_xiso_jmp;

#define main extract_xiso_main
#define exit(code) longjmp(g_xiso_jmp, (code) == 0 ? 1000 : (code))

#include "extract_xiso.c"

#undef main
#undef exit

int extract_xiso_run(int argc, char **argv) {
    optind = 1;
    int rc = setjmp(g_xiso_jmp);
    if (rc == 0) {
        rc = extract_xiso_main(argc, argv);
    } else if (rc == 1000) {
        rc = 0;
    }
    return rc;
}

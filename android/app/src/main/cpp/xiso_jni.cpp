#include <jni.h>
#include <setjmp.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static jmp_buf g_xiso_jmp;

#define main extract_xiso_main
#define exit(code) longjmp(g_xiso_jmp, (code) == 0 ? 1000 : (code))

extern "C" {
#include "extract_xiso.c"
}

#undef main
#undef exit

extern "C" JNIEXPORT jint JNICALL
Java_com_f12012_android_GameDownloader_nativeExtractXiso(JNIEnv* env, jclass, jstring isoPath,
                                                          jstring outDir) {
  const char* iso = env->GetStringUTFChars(isoPath, nullptr);
  const char* out = env->GetStringUTFChars(outDir, nullptr);
  if (!iso || !out) {
    if (iso) env->ReleaseStringUTFChars(isoPath, iso);
    if (out) env->ReleaseStringUTFChars(outDir, out);
    return -1;
  }

  char arg0[] = "extract-xiso";
  char arg1[] = "-d";
  char* arg2 = strdup(out);
  char arg3[] = "-x";
  char* arg4 = strdup(iso);
  char* argv[] = {arg0, arg1, arg2, arg3, arg4, nullptr};

  optind = 1;
  int rc = setjmp(g_xiso_jmp);
  if (rc == 0) {
    rc = extract_xiso_main(5, argv);
  } else if (rc == 1000) {
    rc = 0;
  }

  free(arg2);
  free(arg4);
  env->ReleaseStringUTFChars(isoPath, iso);
  env->ReleaseStringUTFChars(outDir, out);
  return rc;
}

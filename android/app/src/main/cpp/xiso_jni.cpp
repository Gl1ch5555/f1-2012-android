#include <jni.h>
#include <stdlib.h>
#include <string.h>

extern "C" int extract_xiso_run(int argc, char** argv);

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

  int rc = extract_xiso_run(5, argv);

  free(arg2);
  free(arg4);
  env->ReleaseStringUTFChars(isoPath, iso);
  env->ReleaseStringUTFChars(outDir, out);
  return rc;
}

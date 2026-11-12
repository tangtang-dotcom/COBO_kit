package app.module.lib;

/**
 * 微博客户端安全库（SecShell）的 JNI 绑定，包名/类名/方法签名与官方客户端保持一致，
 * 这样 JNI 才能找到 libSecShell.so 里的 Java_app_module_lib_UniqueNative_* 符号。
 * 用于生成容器接口需要的 s（签名）与设备指纹，取代旧的 libwbutil。
 */
public final class UniqueNative {

    private static volatile boolean loaded = false;

    static {
        try {
            System.loadLibrary("SecShell");
            loaded = true;
        } catch (Throwable ignored) {
            loaded = false;
        }
    }

    private UniqueNative() {}

    public static boolean isLoaded() { return loaded; }

    public static native String convert(String input);

    public static native boolean check();

    public static native String key();

    public static native String name();

    public static native void update(String arg);

    public static native void loadFD(String arg);
}

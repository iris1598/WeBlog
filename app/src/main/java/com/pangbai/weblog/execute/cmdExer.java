package com.pangbai.weblog.execute;


import static com.pangbai.weblog.tool.util.getByEnv;


import android.util.Log;

import com.pangbai.weblog.tool.Init;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

public class cmdExer {
    public static Process process;
    public static String result;
    static String cwd = Init.sdcardPath, lastLine;

    /**
     * 启动 shell 的方式，-1 表示还没确定：
     * 1 = /system/bin/linker64 + files 目录下的 busybox（Android 10+ 一直沿用的方案）
     * 0 = 直接执行 nativeLibraryDir/libbusybox.so（apk_data_file 带 execute 权限，可直连）
     * 2 = 直接执行 files 目录下的 busybox（Android 10 以下）
     */
    private static int shellMode = -1;

    /** 候选顺序：优先沿用原有方案，失败再逐级降级 */
    private static final int[] SHELL_MODE_ORDER = {1, 0, 2};

    public static int executeScripts(List<String> name, String path, boolean wait) {
        // 这里用 source（.）而不是 `sh script`：
        // `sh script` 会再 fork+exec 一层 shell，而 libexec.so 在 exec ELF 时会把
        // LD_PRELOAD 从 envp 里剥掉，导致新 shell 没有 execve 钩子，脚本里调用
        // git / hugo 这类 $PREFIX 下的二进制时就会 "Permission denied"。
        // 用 source 让脚本直接跑在当前（带钩子的）shell 里，链路不会断。
        StringBuilder cmd = new StringBuilder();
        String dir = path.endsWith("/") ? path : path + "/";
        int size = name.size();
        for (int i = 0; i < size; i++) {
            cmd.append(". \"").append(dir).append(name.get(i)).append("\"");
            if (i != size - 1) cmd.append(" && ");
        }
        return execute(cmd.toString(), false, wait);
    }


    public static int execute(String command, boolean su) {
        return execute(command, su, true);
    }


    public static int execute(String command, boolean su, boolean wait) {
        result = "";
        BufferedReader reader = null;
        String shell;
        if (su)
            shell = "su";
        else
            shell = "sh";

        ProcessBuilder processBuilder = new ProcessBuilder();
        // Map<String, String> environment = processBuilder.environment();
        if (cwd != null) {
            File dir = new File(cwd);
            // 首次安装时可能还没拿到存储权限，chdir 到 /storage/emulated/0 会失败，
            // 这时退回私有目录，避免整条命令直接抛 IOException。
            if (!dir.isDirectory()) {
                Log.w("exer", "工作目录不可用: " + cwd + "，改用 " + Init.filesDirPath);
                dir = new File(Init.filesDirPath);
            }
            if (dir.isDirectory()) processBuilder.directory(dir);
        }
        for (String env : Init.envp) {
            String[] tmp = getByEnv(env);
            processBuilder.environment().put(tmp[0], tmp[1]);
        }

        processBuilder.redirectErrorStream(true);

        process = null;
        IOException lastError = null;
        for (int i = 0; i < SHELL_MODE_ORDER.length; i++) {
            int mode = SHELL_MODE_ORDER[i];
            // 已经确定过可用方式，就直接用，不再逐级尝试
            if (shellMode >= 0 && mode != shellMode) continue;
            String[] cmd = buildCommand(mode, shell, command);
            if (cmd == null) continue;
            processBuilder.command(cmd);
            try {
                process = processBuilder.start();
                if (shellMode != mode) {
                    Log.i("exer", "shell 启动方式确定为 mode=" + mode + " (" + cmd[0] + ")");
                    shellMode = mode;
                }
                break;
            } catch (IOException e) {
                lastError = e;
                if (shellMode == mode) shellMode = -1; // 缓存的方式失效，重新探测
                Log.w("exer", "shell 启动失败 mode=" + mode + " (" + cmd[0] + "): " + e.getMessage());
            }
        }

        if (process == null) {
            result += lastError == null ? "no available shell" : lastError.getMessage();
            Log.e("exer", "无法启动 shell: " + result);
            return -1;
        }

        try {
            if (!wait)
                return 0;
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {

                result += line + "\n";
                lastLine = line;
                Log.e("exer", line);
            }

            return process.waitFor();
        } catch (IOException | InterruptedException e) {
            result += e.getMessage();
            e.printStackTrace();
            return -1;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (process != null && wait) {
                process.destroy();
            }
        }
    }

    private static String[] buildCommand(int mode, String shell, String command) {
        switch (mode) {
            case 0:
                // Android 10+：files 目录里的 ELF 已经没有 execute 权限，
                // 而 nativeLibraryDir 下的 lib*.so 仍带 x_file_perms，可以直接 execve。
                if (Init.busyboxNativePath == null || !new File(Init.busyboxNativePath).isFile())
                    return null;
                return new String[]{Init.busyboxNativePath, shell, "-c", command};
            case 1:
                if (!Init.android10) return null;
                return new String[]{Init.linker, Init.busyboxPath, shell, "-c", command};
            default:
                return new String[]{Init.busyboxPath, shell, "-c", command};
        }
    }

    public static void destroy() {
        process = null;
        result = null;
        lastLine = null;
    }

    public static String getLastLine() {
        return lastLine;
    }

    public static void setCwd(String path) {
        cwd = path;
    }

    public static Process getProcess() {
        return process;
    }
}

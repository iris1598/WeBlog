package com.pangbai.weblog.execute;

import com.pangbai.weblog.project.Project;
import com.pangbai.weblog.tool.Init;

import java.util.ArrayList;

public abstract class BlogCmd {



    public static String checkEnvironment() {
        StringBuilder res = new StringBuilder();
        res.append("Node : ").append(version("node -v")).append('\n');
        res.append("Npm  : ").append(version("npm -v")).append('\n');
        res.append("Hugo : ").append(version("hugo version")).append('\n');
        res.append("Hexo : ").append(version("hexo version")).append('\n');
        res.append("Git  : ").append(version("git --version")).append('\n');
        res.append('\n').append(Init.describeEnvironment());
        return res.toString();
    }

    private static String version(String cmd) {
        if (cmdExer.execute(cmd, false) != 0) return "Not found";
        String out = cmdExer.result == null ? "" : cmdExer.result.trim();
        if (out.isEmpty()) return "OK";
        int end = out.indexOf('\n');
        return end > 0 ? out.substring(0, end) : out;
    }

    public static void setProjectPath(String path) {
        cmdExer.setCwd(path);
    }

    public abstract boolean changeConfig(String key, String value);

    public abstract boolean initBlog(String path);

    public abstract Process Server(String port, boolean serverFlag);
}

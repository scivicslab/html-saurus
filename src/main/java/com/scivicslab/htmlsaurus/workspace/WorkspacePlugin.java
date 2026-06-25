package com.scivicslab.htmlsaurus.workspace;

import com.scivicslab.aiworkspace.spi.WorkspaceToolPlugin;

import java.util.List;

public final class WorkspacePlugin implements WorkspaceToolPlugin {

    @Override public String toolName()    { return "html-saurus"; }
    @Override public String jarFileName() { return "html-saurus.jar"; }
    @Override public int defaultPort()    { return 28110; }
    @Override public String githubRepo()  { return "scivicslab/html-saurus"; }

    @Override
    public List<String> args() {
        return List.of("${HOME}/works", "--portal-mode", "--serve", "--port", "${PORT}");
    }

    @Override
    public List<ParamDef> params() {
        return List.of(
            new ParamDef("dir", "Document Root", "dir",
                "${HOME}/works", null, false, 0, List.of())
        );
    }
}

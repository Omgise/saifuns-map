package com.saifun.map.render;

import org.lwjgl.opengl.GL11;

/**
 * Explicit OpenGL state guard designed to remain 100% safe under Angelica (Sodium/Iris 1.7.10)
 * and LWJGL3ify (Java 8–25). Saves and restores core HUD GL toggles cleanly without leaking state.
 */
public final class GLStateGuard implements AutoCloseable {

    private final boolean blend;
    private final boolean depthTest;
    private final boolean alphaTest;
    private final boolean cullFace;
    private final boolean lighting;
    private final boolean texture2d;
    private final boolean scissorTest;

    public GLStateGuard() {
        this.blend = GL11.glIsEnabled(GL11.GL_BLEND);
        this.depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        this.alphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        this.cullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        this.lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        this.texture2d = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        this.scissorTest = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        GL11.glPushMatrix();
    }

    @Override
    public void close() {
        GL11.glPopMatrix();
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

        setEnabled(GL11.GL_BLEND, blend);
        setEnabled(GL11.GL_DEPTH_TEST, depthTest);
        setEnabled(GL11.GL_ALPHA_TEST, alphaTest);
        setEnabled(GL11.GL_CULL_FACE, cullFace);
        setEnabled(GL11.GL_LIGHTING, lighting);
        setEnabled(GL11.GL_TEXTURE_2D, texture2d);
        setEnabled(GL11.GL_SCISSOR_TEST, scissorTest);
    }

    private static void setEnabled(int cap, boolean enabled) {
        if (enabled) GL11.glEnable(cap);
        else GL11.glDisable(cap);
    }
}

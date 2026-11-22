package com.saifun.map.radar;

public enum EntityCategory {
    PLAYER(0xFF4FC3F7, 1.15f),
    BOSS(0xFFFF5252, 1.30f),
    HOSTILE(0xFFFF6B6B, 0.95f),
    TAMED(0xFF69F0AE, 0.90f),
    PASSIVE(0xFFFFEE58, 0.85f),
    ITEM(0xFFCFD8DC, 0.65f),
    OTHER(0xFFB0BEC5, 0.80f);

    public final int color;
    public final float defaultScale;

    EntityCategory(int color, float defaultScale) {
        this.color = color;
        this.defaultScale = defaultScale;
    }
}

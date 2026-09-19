package com.signedcontract.model;

public record BoundingBox(int x, int y, int width, int height) {
    public BoundingBox withMargin(int margin, int pageW, int pageH) {
        int nx = Math.max(0, x - margin);
        int ny = Math.max(0, y - margin);
        int nw = Math.min(pageW - nx, width + 2 * margin);
        int nh = Math.min(pageH - ny, height + 2 * margin);
        return new BoundingBox(nx, ny, nw, nh);
    }
}

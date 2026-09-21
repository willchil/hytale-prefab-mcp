package games.crescentnetwork.mcp.render;

/**
 * A pinhole camera orbiting a build.
 *
 * <p>Parameterised the way an agent inspecting its own work wants to think about it: an angle around
 * the build and an angle above it, with the distance solved automatically unless overridden. Explicit
 * eye and target positions are available through {@link #looking} for the cases where an exact
 * viewpoint matters.
 */
public final class Camera {

    public final double eyeX, eyeY, eyeZ;
    /** Camera basis, all unit length and mutually perpendicular. */
    public final double fwdX, fwdY, fwdZ;
    public final double rightX, rightY, rightZ;
    public final double upX, upY, upZ;
    /** Half-extent of the image plane at unit distance. */
    public final double halfWidth, halfHeight;

    private Camera(double eyeX, double eyeY, double eyeZ,
                   double fwdX, double fwdY, double fwdZ,
                   double rightX, double rightY, double rightZ,
                   double upX, double upY, double upZ,
                   double halfWidth, double halfHeight) {
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.fwdX = fwdX;
        this.fwdY = fwdY;
        this.fwdZ = fwdZ;
        this.rightX = rightX;
        this.rightY = rightY;
        this.rightZ = rightZ;
        this.upX = upX;
        this.upY = upY;
        this.upZ = upZ;
        this.halfWidth = halfWidth;
        this.halfHeight = halfHeight;
    }

    /**
     * Places the camera on an orbit around {@code target}.
     *
     * @param yaw      degrees around the vertical axis; 0 looks along +Z, 90 along +X
     * @param pitch    degrees above the horizon, clamped to just inside straight down/up so the
     *                 up vector never degenerates
     * @param distance eye-to-target distance
     * @param fovDeg   vertical field of view
     */
    public static Camera orbiting(double[] target, double yaw, double pitch, double distance,
                                  double fovDeg, int width, int height) {
        double p = Math.toRadians(Math.max(-89.9, Math.min(89.9, pitch)));
        double y = Math.toRadians(yaw);

        double dirX = Math.cos(p) * Math.sin(y);
        double dirY = Math.sin(p);
        double dirZ = Math.cos(p) * Math.cos(y);

        double[] eye = {
            target[0] + dirX * distance,
            target[1] + dirY * distance,
            target[2] + dirZ * distance
        };
        return looking(eye, target, fovDeg, width, height);
    }

    /** Places the camera at {@code eye} aimed at {@code target}. */
    public static Camera looking(double[] eye, double[] target, double fovDeg, int width, int height) {
        double fx = target[0] - eye[0];
        double fy = target[1] - eye[1];
        double fz = target[2] - eye[2];
        double flen = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (flen < 1e-9) {
            fx = 0;
            fy = 0;
            fz = 1;
            flen = 1;
        }
        fx /= flen;
        fy /= flen;
        fz /= flen;

        // A camera looking straight down has no meaningful "up" against world up, so lean the
        // reference axis over to keep the cross products well conditioned.
        double refX = 0, refY = 1, refZ = 0;
        if (Math.abs(fy) > 0.999) {
            refX = 0;
            refY = 0;
            refZ = 1;
        }

        double rx = fy * refZ - fz * refY;
        double ry = fz * refX - fx * refZ;
        double rz = fx * refY - fy * refX;
        double rlen = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rlen;
        ry /= rlen;
        rz /= rlen;

        double ux = ry * fz - rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy - ry * fx;

        double halfH = Math.tan(Math.toRadians(Math.max(5, Math.min(120, fovDeg))) / 2.0);
        double halfW = halfH * ((double) width / Math.max(1, height));

        return new Camera(eye[0], eye[1], eye[2], fx, fy, fz, rx, ry, rz, ux, uy, uz, halfW, halfH);
    }

    /**
     * A distance that fits a box of the given size in view at this field of view and aspect.
     *
     * <p>Solved against the box's bounding sphere rather than the box itself, so the framing holds at
     * every yaw and pitch instead of only the one it was computed for.
     */
    public static double fitDistance(double sizeX, double sizeY, double sizeZ,
                                     double fovDeg, int width, int height) {
        double radius = 0.5 * Math.sqrt(sizeX * sizeX + sizeY * sizeY + sizeZ * sizeZ);
        double vFov = Math.toRadians(Math.max(5, Math.min(120, fovDeg)));
        double aspect = (double) width / Math.max(1, height);
        // The tighter of the two half-angles is what actually clips the build.
        double hFov = 2.0 * Math.atan(Math.tan(vFov / 2.0) * aspect);
        double limiting = Math.min(vFov, hFov);
        double distance = radius / Math.sin(limiting / 2.0);
        // A little air around the subject reads better than an exactly tangent fit.
        return Math.max(1.5, distance * 1.12);
    }

    /** Ray direction through a pixel, given normalised device coordinates in [-1, 1]. */
    public double[] rayDirection(double ndcX, double ndcY) {
        double dx = fwdX + rightX * (ndcX * halfWidth) + upX * (ndcY * halfHeight);
        double dy = fwdY + rightY * (ndcX * halfWidth) + upY * (ndcY * halfHeight);
        double dz = fwdZ + rightZ * (ndcX * halfWidth) + upZ * (ndcY * halfHeight);
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[]{dx / len, dy / len, dz / len};
    }
}

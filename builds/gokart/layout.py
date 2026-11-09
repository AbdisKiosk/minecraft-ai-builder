"""Shared 2D plan for the go-kart venue. Venue-local coords: X 0..49 (east), Z 0..34 (south)."""
import math

X0, Z0, GY = -236, 152, 64      # world origin of the venue, ground level (surface block y)
W, D = 50, 35

HALF = 2.0                       # track half-width -> 4 wide (centerline on half coords)
CURB = 3.0

TRACK = [(27.5, 27.5), (27.5, 6.5), (6.5, 6.5), (6.5, 27.5), (12.5, 27.5),
         (15.5, 22.5), (20.5, 22.5), (23.5, 27.5)]
PIT = [(28.0, 16.0), (30.5, 20.0), (30.5, 26.0), (33.0, 28.0), (37.0, 28.0)]
BARRIER = (2, 32, 2, 32)         # x0, x1, z0, z1 of the track's barrier ring


def chaikin(pts, n=3, closed=True):
    for _ in range(n):
        out = []
        m = len(pts) if closed else len(pts) - 1
        for i in range(m):
            (ax, az), (bx, bz) = pts[i], pts[(i + 1) % len(pts)]
            out += [(0.75 * ax + 0.25 * bx, 0.75 * az + 0.25 * bz), (0.25 * ax + 0.75 * bx, 0.25 * az + 0.75 * bz)]
        if not closed:
            out = [pts[0]] + out + [pts[-1]]
        pts = out
    return pts


def seg_dist(px, pz, a, b):
    (ax, az), (bx, bz) = a, b
    dx, dz = bx - ax, bz - az
    t = max(0.0, min(1.0, ((px - ax) * dx + (pz - az) * dz) / (dx * dx + dz * dz or 1)))
    return math.hypot(px - ax - t * dx, pz - az - t * dz)


def poly_dist(px, pz, pts, closed):
    n = len(pts) if closed else len(pts) - 1
    return min(seg_dist(px, pz, pts[i], pts[(i + 1) % len(pts)]) for i in range(n))


TRACK_S = chaikin(TRACK, 3, True)
PIT_S = chaikin(PIT, 3, False)


def inside_poly(x, z, pts):
    c = False
    for i in range(len(pts)):
        (ax, az), (bx, bz) = pts[i], pts[i - 1]
        if (az > z) != (bz > z) and x < (bx - ax) * (z - az) / (bz - az) + ax:
            c = not c
    return c


def near_corner(x, z, r=6.5):
    return any(math.hypot(x - cx, z - cz) < r for cx, cz in TRACK)


def plan():
    """cell -> kind: asphalt, pit, curb_r, curb_w, edge, gravel, grass"""
    bx0, bx1, bz0, bz1 = BARRIER
    cells = {}
    for X in range(W):
        for Z in range(D):
            inside = bx0 < X < bx1 and bz0 < Z < bz1
            d = poly_dist(X, Z, TRACK_S, True)
            dp = poly_dist(X, Z, PIT_S, False)
            if d <= HALF:
                k = "asphalt"
            elif dp <= 1.1 and X >= 29:
                k = "pit"
            elif d <= CURB and inside:
                if near_corner(X, Z):
                    k = "curb_r" if ((X + Z) // 2) % 2 == 0 else "curb_w"
                else:
                    k = "edge"
            elif inside and d <= CURB + 2.5 and near_corner(X, Z, 8) and not inside_poly(X, Z, TRACK_S):
                k = "gravel"
            else:
                k = "grass"
            cells[(X, Z)] = k
    return cells


if __name__ == "__main__":
    c = plan()
    ch = {"asphalt": "#", "pit": "=", "curb_r": "r", "curb_w": "w", "edge": "|", "gravel": ":", "grass": "."}
    bx0, bx1, bz0, bz1 = BARRIER
    for Z in range(D):
        row = ""
        for X in range(W):
            onb = (X in (bx0, bx1) and bz0 <= Z <= bz1) or (Z in (bz0, bz1) and bx0 <= X <= bx1)
            row += "B" if onb and c[(X, Z)] not in ("asphalt", "pit") else ch[c[(X, Z)]]
        print(f"{Z:2d} {row}")

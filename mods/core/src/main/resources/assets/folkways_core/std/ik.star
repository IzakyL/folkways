load("@std//math", "sqrt")

chain = _ik.chain
forward = _ik.forward
solve = _ik.solve
fit_revolute = _ik.fit_revolute
fit_prismatic = _ik.fit_prismatic

def to_local(frame, point):
    """A world point in the space of a moving structure, given its frame: `{"origin", "x", "y", "z"}` in world terms."""
    d = [point[i] - frame["origin"][i] for i in range(3)]
    return [d[0] * frame[k][0] + d[1] * frame[k][1] + d[2] * frame[k][2] for k in ["x", "y", "z"]]

def to_world(frame, point):
    """A point in a moving structure's space, in the world, given the structure's frame."""
    return [frame["origin"][i] + point[0] * frame["x"][i] + point[1] * frame["y"][i] + point[2] * frame["z"][i]
            for i in range(3)]

def _abs(x):
    return -x if x < 0 else x

def _dot(a, b):
    total = 0.0
    for i in range(len(a)):
        total += a[i] * b[i]
    return total

def _solve_linear(m, rhs):
    # Gaussian elimination with partial pivoting; None when the system is singular.
    n = len(rhs)
    a = [list(m[i]) + [rhs[i]] for i in range(n)]
    for col in range(n):
        pivot = col
        for row in range(col + 1, n):
            if _abs(a[row][col]) > _abs(a[pivot][col]):
                pivot = row
        if _abs(a[pivot][col]) < 1e-9:
            return None
        swap = a[col]
        a[col] = a[pivot]
        a[pivot] = swap
        for row in range(n):
            if row != col:
                f = a[row][col] / a[col][col]
                a[row] = [a[row][k] - f * a[col][k] for k in range(n + 1)]
    return [a[i][n] / a[i][i] for i in range(n)]

def fit_straight(samples):
    """A prismatic chain fitted by least squares to `[controls, [x, y, z]]` samples, or None if the fit is degenerate."""
    if not samples or not samples[0][0] or len(samples) <= len(samples[0][0]):
        return None
    n = len(samples[0][0])
    rows = [[1.0] + [float(v) for v in sample[0]] for sample in samples]
    m = [[_dot([row[i] for row in rows], [row[j] for row in rows]) for j in range(n + 1)] for i in range(n + 1)]
    columns = []
    for axis in range(3):
        rhs = [_dot([row[i] for row in rows], [sample[1][axis] for sample in samples]) for i in range(n + 1)]
        fit = _solve_linear(m, rhs)
        if fit == None:
            return None
        columns.append(fit)
    joints = []
    for i in range(n):
        a = [columns[axis][i + 1] for axis in range(3)]
        length = sqrt(_dot(a, a))
        if length < 1e-6:
            return None
        joints.append({"kind": "prismatic", "direction": [v / length for v in a], "per_unit": length, "zero": 0.0})
    return chain(joints, [columns[axis][0] for axis in range(3)])

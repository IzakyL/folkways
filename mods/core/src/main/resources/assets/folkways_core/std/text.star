def truncate(s, n):
    if n < 0:
        fail("n must be nonnegative")
    if len(s) <= n:
        return s
    if n == 0:
        return ""
    return s[:n - 1] + "…"

def table(rows, columns=None, limit=50):
    if limit < 0:
        fail("limit must be nonnegative")
    rows = rows[:limit]
    if columns == None:
        columns = list(rows[0].keys()) if rows else []
    if not columns:
        return ""
    lines = [" | ".join([str(column) for column in columns])]
    for row in rows:
        lines.append(" | ".join([str(row.get(column, "")) for column in columns]))
    return "\n".join(lines)

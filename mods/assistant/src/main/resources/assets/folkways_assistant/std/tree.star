def find(pattern, where=None, limit=100):
    if type(limit) != "int" or limit < 0:
        fail("limit must be a nonnegative integer")
    found = []
    if limit == 0:
        return found
    for path in glob(pattern):
        value = get(path)
        if where == None or where(value):
            found.append({"path": path, "value": value})
            if len(found) == limit:
                break
    return found

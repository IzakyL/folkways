package io.github.izakyl.folkways.assistant.tool.internal;

final class StateHelp {

    static final String RENAME = """
            id = ls("residents")[0]
            set("residents/" + id + "/name", "Ada")""";

    private StateHelp() {
    }

    static String describe() {
        return """
                ls(path) lists sorted child segments. ls("") lists the root. glob(pattern) lists sorted paths,
                with * matching one segment. get(path) reads the starting snapshot plus staged changes.
                set(path, value) stages a write. delete(path) stages removal. A batch previews the final changes.
                Escape literal / in keys as %2F and % as %25. Use segments returned by ls directly in paths.

                Read colony, colony/problems, residents/<id>, residents/<id>/trace,
                surveys/<id>, and routines/<id>.
                Write residents/<id>/name, residents/<id>/work/<vocation>, and work_defaults/<kind>/<vocation>.
                Work writes merge allowed and keenness fields.
                Write or delete scripts/<name> as Starlark source. Load a library with load("//scripts/<name>", ...).

                List facilities with ls("facilities"). Use facilities/<id or name> to read a facility or delete
                a held zone or path. Read or write parameters/<key> under a facility, including settings scopes.
                Read readings, recent, and runs/<id> under a facility.
                For player-defined facilities, read or write definition, triggers/<name>,
                triggers/<name>/{enabled,retries,backoff}, and routines/<name> under the facility.
                Definitions and routines also support deletion. Express behavior with {source, with}.
                Optional library routines use {use, with}. A reading {source} defines read(readings, params).
                Gate open_when and card enabled_when accept comparisons or source defining when(readings).
                Routine sources can define path(readings, args), readings(readings, args), ready(readings, args),
                and check(args). A workshop selects a script by routine name. Use recipes(input) with recipes_of(type_id),
                or declare a fixed batch list. step(state, input, args) receives args.recipe and args.demand for
                workshop runs. Actions are write, write_sequence, await, run, deliver, collect, done, fail and
                fail_permanent. Set on_fail="handler" to call handler(state, failure, input, args) on node failure.

                set("facilities/<new name>", {kind, dimension, from, to, parameters?}) stages a zone.
                Use points instead of from/to for a path. Initial parameters are applied when the drawing is filed.
                set("builds/<new name>", {pattern or source, dimension, at, to?, rotation?, mirror?, settings?})
                stages a survival build with ghost blocks in the confirmation. Use points instead of at/to for a line.
                Exactly one of pattern and source is required. Read builds/<id> and delete it to cancel the order.
                set("surveys/<new name>", {dimension, points}) stages a survey corridor.
                set("facilities/<id>/runs/<new name>", {routine, args?}) stages a manual routine.
                Runs start after other state writes. Delete facilities/<id>/runs/<run id> to cancel an active script run.
                Creation paths use labels for the current script. After confirmation, ls lists the assigned IDs.
                Coordinates use {x, y, z}. Destructive changes always require confirmation.

                Read client/marks for the Colony Book tool, dimension, tick and corners or points
                captured when the question was sent. It returns None when there are no marks.
                A box over a moving structure adds structures: {structure, min, max} in its local cells.
                """ + "\n" + RENAME;
    }
}

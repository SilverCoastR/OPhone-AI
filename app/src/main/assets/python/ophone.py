"""OPhone AI helpers for scripts run by python_run or terminal_start.

report() prints a status line that OPhone AI parses into the job receipt and
pushes to the agent as a terminal.report event. Call it at key points: start,
each phase, anything unexpected, and the final result.
"""
import json
import sys

__all__ = ["report", "step"]


def report(stage, message="", progress=None, level="info", **data):
    rec = {"stage": str(stage)[:80], "message": str(message)[:500], "level": level if level in ("info", "warn", "error") else "info"}
    if progress is not None:
        rec["progress"] = max(0.0, min(1.0, float(progress)))
    if data:
        rec["data"] = data
    sys.stdout.write("@@ophone:report " + json.dumps(rec, ensure_ascii=False, default=str) + "\n")
    sys.stdout.flush()


class step:
    """with step("download", "fetching 3 files"): ... reports start, done, or the error."""

    def __init__(self, stage, message=""):
        self.stage, self.message = stage, message

    def __enter__(self):
        report(self.stage, self.message or "started")
        return self

    def __exit__(self, kind, value, tb):
        if kind is None:
            report(self.stage, "done")
        else:
            report(self.stage, "%s: %s" % (kind.__name__, value), level="error")
        return False

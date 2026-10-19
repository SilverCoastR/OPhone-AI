# Runtime autonomy

OPhone AI is designed to let the on-phone agent diagnose and continue its own work.

The runtime discovers capabilities instead of assuming that a permission or executable exists. A task stores its goal, success criteria, checkpoints, operations, event deliveries, and final state in app-private storage. After a reload, the provider can query the native task record and continue from the last confirmed step.

Mutating operations emit completion or failure events. The runtime prefers structured completion signals, Android window changes, package state, files, and HTTP results. UI-tree inspection and screenshots remain available when a structured signal cannot prove the outcome.

Recovery is bounded: the runtime reports a concise diagnosis, retries only when the operation is safe to repeat, and asks the model for a different plan when the same failure persists. Finishing a task requires fresh evidence against explicit success criteria.

The device cannot silently grant Android permissions, bypass system consent dialogs, or obtain root. Those remain user or operating-system decisions.

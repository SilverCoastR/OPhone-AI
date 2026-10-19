# Root shell

The normal terminal runs as the OPhone AI application UID. This is the supported default on stock Android.

Root mode is disabled by default and guarded by a dedicated settings switch with a high-risk explanation. Enabling the switch does not root a phone. The runtime probes for a working `su` command, reports the result, and only then allows a root terminal request.

A root process can read or modify data outside the Android application sandbox and can make a device unstable. Root jobs keep the same task ownership, timeout, output, stop, and audit controls as normal terminal jobs, but those controls cannot reverse changes already made by a privileged command.

/* Executable entry point for the embedded CPython runtime.
 * Packaged as lib/<abi>/libophone_python.so so Android installs it in the
 * native library directory, the only app location an ordinary app may exec. */
#include <Python.h>

int main(int argc, char **argv) {
    return Py_BytesMain(argc, argv);
}

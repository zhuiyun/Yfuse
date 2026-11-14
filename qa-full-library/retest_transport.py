"""Run the complete installed app before independent source-byte diagnosis."""
import run
import transport_audit

original_controls = run.controls


def controls_with_startup_operations(prefix, media):
    run.measure.measured(prefix + '-startup-resume', verified=True, **run.pilot.pause_resume(prefix, media))
    run.fast_forward(prefix, media)
    original_controls(prefix, media)


if __name__ == '__main__':
    run.controls = controls_with_startup_operations
    try:
        run.main()
    finally:
        payload = run.measure.load_envelope()
        transport_audit.inspect(payload['account'], payload['samples'])

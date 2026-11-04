# Local evidence acceptance

`bidcheck_acceptance.py` is an offline acceptance tool. Pass the customer-provided
case configuration with `--cases <path>`; the real configuration, baseline,
confirmation exports, source documents, and model logs are kept outside the
public repository. Python is used only for local acceptance tooling; production
document checking and Agent execution run in Java 8.

The two authorized Java adaptations check actual HTTP retry/deadline behavior
and complete Maven/Surefire execution counts. Business cases and thresholds are
read from the original case configuration. Date-dependent acceptance requires
an explicitly confirmed reference date.

`AcceptanceReport.java` exports comparisons from saved run reports.
`TicketAcceptance.java` is an auxiliary Java checker; the requested Python
acceptance remains the final verifier for the current ticket package.

Some local regression tests require private document fixtures or private test
helpers and are excluded by `.gitignore`. A public checkout does not reproduce
the full private-document acceptance suite. Do not publish its source documents
or logs to make the fixture-dependent checks runnable.

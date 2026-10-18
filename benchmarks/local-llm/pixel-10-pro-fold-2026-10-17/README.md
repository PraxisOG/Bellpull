# Pixel 10 Pro Fold: Phase 13 local inference

Synthetic measurements from Bellpull 0.13.0 experimental. See
[scope, test results and interpretation](../../../docs/PHASE-13-LOCAL-LLM.md).
The phone was USB-powered. These short runs do not measure battery life or prove
sustained thermal performance. CPU and GPU correctness results are separate from
speed; failed GPU output is not a usable acceleration result.

E4B testing ran with airplane mode enabled and Wi-Fi disabled. Android retains the
legacy `mobile_data` preference at 1 even while airplane mode turns off cellular
radios. Before/after settings match, including the restored physical display size.
Model weights are not included. Model SHA-256 and the exact native revision appear
in each report. Prompts/results contain synthetic data only.

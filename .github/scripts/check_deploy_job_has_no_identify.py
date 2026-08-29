#!/usr/bin/env python3
"""Fail if Deploy Production's deploy job runs public-key identify.

That script is diagnostic-only (SSH access check / backup-alert).
Deploy does not use its output. A missing runner checkout used to abort
SSH before appleboy ran (Actions run 33237217036).
"""
from pathlib import Path

WORKFLOW = Path(".github/workflows/deploy-production.yml")


def deploy_job_body(text: str) -> str:
    start = text.index("\n  deploy:\n")
    end = text.index("\n  start-offbox:\n")
    return "\n".join(
        line
        for line in text[start:end].splitlines()
        if not line.lstrip().startswith("#")
    )


def main() -> None:
    code = deploy_job_body(WORKFLOW.read_text())
    if "identify_deploy_pubkey.sh" in code:
        raise SystemExit("deploy job must not call identify_deploy_pubkey.sh")
    if "Identify deploy public key" in code:
        raise SystemExit("deploy job must not identify the deploy public key")
    print("deploy_job_has_no_identify_ok")


if __name__ == "__main__":
    main()

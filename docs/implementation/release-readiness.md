# Release readiness

Target: the confirmed P0 mobile MVP in `docs/betchu기획안.md`. No payments, P1/P2 gameplay, or speculative dependency upgrades are included.

Implementation sequence:

1. Economy: rescue quests, immutable draw odds and independent pity, inventory, equipment, wallet history.
2. Cooperation: weekly baseline, comparison, bilateral weekend boss participation, atomic ticket rewards.
3. Safety: private reports, authenticated export, recent-login account deletion, retention and session revocation.
4. Delivery: actionable inbox and notification lifecycle, production configuration validation, automated checks and an operations runbook.
5. Mobile integration: complete screens, errors and retry behavior, release configuration, accessibility and integrated regression checks.

Changes belong on `backend` and `frontend`; verified integration belongs on `work`. The existing local change to `Betchu-backend/infra/compose.yaml` is outside this work and must remain untouched.

Completion evidence and external release gates will be recorded here as each stage is verified. Real provider credentials, published approved policies, store signing and physical-device verification cannot be represented by local tests.

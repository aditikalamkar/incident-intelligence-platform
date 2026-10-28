-- Local development only (loaded by the "dev" profile).
-- API key: opspilot-dev-key
-- Admin login: admin@demo.local / opspilot-dev-password
INSERT INTO tenant (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Startup')
ON CONFLICT (id) DO NOTHING;

INSERT INTO api_key (id, tenant_id, key_hash, label)
VALUES ('00000000-0000-0000-0000-0000000000a1',
        '00000000-0000-0000-0000-000000000001',
        'f781f11f383aae22e79bf8c9851f9faf17c31fd150078b815f38454d996d2690',
        'dev key')
ON CONFLICT (id) DO NOTHING;

INSERT INTO app_user (id, tenant_id, email, name, role, password_hash)
VALUES ('00000000-0000-0000-0000-0000000000b1',
        '00000000-0000-0000-0000-000000000001',
        'admin@demo.local',
        'Demo Admin',
        'ADMIN',
        '{bcrypt}$2a$10$vf5tt5ZSLVbNkcMwxXvaZuxJzgXzM1IXn/ekM2r7TB9IpqJp3whhO')
ON CONFLICT (id) DO NOTHING;

-- The Payments team owns payment-api and pages the admin, so demo incidents are escalated.
INSERT INTO team (id, tenant_id, name)
VALUES ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-000000000001', 'Payments')
ON CONFLICT DO NOTHING;

INSERT INTO team_member (team_id, user_id)
VALUES ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000b1')
ON CONFLICT DO NOTHING;

INSERT INTO service (id, tenant_id, name)
VALUES ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-000000000001', 'payment-api')
ON CONFLICT DO NOTHING;

UPDATE service SET team_id = '00000000-0000-0000-0000-0000000000c1'
WHERE tenant_id = '00000000-0000-0000-0000-000000000001' AND name = 'payment-api' AND team_id IS NULL;

INSERT INTO escalation_policy (id, tenant_id, team_id, steps)
VALUES ('00000000-0000-0000-0000-0000000000e1',
        '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-0000000000c1',
        '[{"userIds": ["00000000-0000-0000-0000-0000000000b1"], "escalateAfterMinutes": 5}]')
ON CONFLICT DO NOTHING;

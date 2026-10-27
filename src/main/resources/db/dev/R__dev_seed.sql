-- Local development only (loaded by the "dev" profile). API key: opspilot-dev-key
INSERT INTO tenant (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Startup')
ON CONFLICT (id) DO NOTHING;

INSERT INTO api_key (id, tenant_id, key_hash, label)
VALUES ('00000000-0000-0000-0000-0000000000a1',
        '00000000-0000-0000-0000-000000000001',
        'f781f11f383aae22e79bf8c9851f9faf17c31fd150078b815f38454d996d2690',
        'dev key')
ON CONFLICT (id) DO NOTHING;

ALTER TABLE app_user ADD COLUMN password_hash VARCHAR(100) NOT NULL;

-- Login is by email alone, so an email must identify exactly one user across all tenants.
ALTER TABLE app_user DROP CONSTRAINT app_user_tenant_id_email_key;
CREATE UNIQUE INDEX uq_app_user_email ON app_user (lower(email));

CREATE TABLE team_member (
    team_id UUID NOT NULL REFERENCES team (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    PRIMARY KEY (team_id, user_id)
);
CREATE INDEX idx_team_member_user ON team_member (user_id);

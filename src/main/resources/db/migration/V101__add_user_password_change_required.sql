-- U20-T07: a person added with an initial password sets their own at first sign-in.
ALTER TABLE app_user ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT FALSE;

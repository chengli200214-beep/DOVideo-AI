-- Old in-flight tasks default to an unconfirmed snapshot and fail closed on a later confirmation.
ALTER TABLE storyboard_model_tasks ADD COLUMN expected_confirmation_revision INT NULL;

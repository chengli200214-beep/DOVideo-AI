ALTER TABLE generation_tasks ADD COLUMN submitted_at BIGINT NULL;
ALTER TABLE generation_tasks ADD COLUMN poll_deadline_at BIGINT NULL;

-- Use durable submission events where available; do not invent an acceptance time for legacy tasks.
UPDATE generation_tasks
SET submitted_at = (
    SELECT MIN(occurred_at) FROM generation_events
    WHERE generation_events.task_id = generation_tasks.id AND generation_events.state = 'SUBMITTING'
);

-- Legacy RUNNING/SAVING tasks receive one persisted query window when next claimed.
-- Migration and recovery never move a task back to QUEUED or issue another paid submission.

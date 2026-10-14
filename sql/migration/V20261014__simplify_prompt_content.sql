-- Simplify Prompt versions to one complete Prompt content field.
-- Prerequisite: V20261010__prompt_version_management.sql has been applied.
-- Back up t_prompt_version before applying. Existing fields are merged in order:
-- system_prompt, user_template, output_schema.

ALTER TABLE `t_prompt_version`
    ADD COLUMN `prompt_content` longtext NULL COMMENT '完整Prompt正文' AFTER `version_no`;

UPDATE `t_prompt_version`
SET `prompt_content` = CONCAT_WS(
        '\n\n',
        NULLIF(`system_prompt`, ''),
        NULLIF(`user_template`, ''),
        NULLIF(`output_schema`, '')
    )
WHERE `prompt_content` IS NULL;

ALTER TABLE `t_prompt_version`
    MODIFY COLUMN `prompt_content` longtext NOT NULL COMMENT '完整Prompt正文',
    DROP COLUMN `system_prompt`,
    DROP COLUMN `user_template`,
    DROP COLUMN `output_schema`;

-- Rollback:
-- Restore the backup of t_prompt_version. A lossless automatic rollback is not
-- possible because the three old fields are intentionally merged into one field.

package com.aiops.entity;

public enum SourceType {
    TEAMS,
    OUTLOOK,
    CONFLUENCE,
    CODE,
    /** Any other uploaded file (design docs, runbooks, specs) that isn't from Confluence, Teams or email. */
    DOCUMENT
}

package dev.aurum.audit;

public enum AuditAction {
    CREATE_USER,
    CHANGE_USER_ROLE,
    CREATE_ACCOUNT,
    FREEZE_ACCOUNT,
    UNFREEZE_ACCOUNT,
    CLOSE_ACCOUNT,
    FUND,
    WITHDRAW,
    TRANSFER,
    REVERSAL,
    REBUILD_PROJECTIONS
}

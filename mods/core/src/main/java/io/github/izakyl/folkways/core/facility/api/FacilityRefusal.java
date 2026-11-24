package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;

/** Why a write to a control, or a wait on what it shows, did not go through. */
public enum FacilityRefusal implements RefusalKind.Named {

    // Someone else holds the control's lease.
    BUSY,
    // The control is gone, unloaded or will not take writes now.
    GONE,
    // The control turned the value down.
    REFUSED,
    // What the control shows has not come to the value written.
    UNSETTLED,
    // The routine refused the call before it could run.
    INVALID,
    POST_UNAVAILABLE,
    SEQUENCE_STEPS,
    SEQUENCE_STANCE,
    POST_GOODS_MISSING,
    POSITION_RESPONSE,
    POSITION_TRAVEL,
    POSITION_UNREACHED,
    SEQUENCE_INTERRUPTED
}

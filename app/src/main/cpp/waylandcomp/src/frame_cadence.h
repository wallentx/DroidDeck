#ifndef DROIDDECK_FRAME_CADENCE_H
#define DROIDDECK_FRAME_CADENCE_H

#include <stdint.h>

#define DD_FRAME_CADENCE_DEFAULT_NS 16666667LL
#define DD_FRAME_CADENCE_MIN_NS      3000000LL
#define DD_FRAME_CADENCE_MAX_NS     40000000LL

struct dd_frame_cadence {
    int64_t last_frame_ns;
    int64_t refresh_ns;
    int64_t candidate_ns;
    unsigned candidate_samples;
};

#define DD_FRAME_CADENCE_INITIALIZER \
    { .last_frame_ns = 0, .refresh_ns = DD_FRAME_CADENCE_DEFAULT_NS, \
      .candidate_ns = 0, .candidate_samples = 0 }

enum dd_frame_cadence_result {
    DD_FRAME_CADENCE_REJECTED = 0,
    DD_FRAME_CADENCE_BASELINED,
    DD_FRAME_CADENCE_UPDATED,
    DD_FRAME_CADENCE_REBASED,
};

static inline int64_t dd_frame_cadence_distance(int64_t a, int64_t b) {
    return a >= b ? a - b : b - a;
}

/* Estimate the display period from Choreographer's frame timeline, independently of when the
 * compositor thread drains the input pipe. A long gap starts a new timeline. A materially new
 * in-range period must repeat once before it replaces the old rate, so one skipped callback does
 * not turn 60 Hz into 30 Hz. */
static inline enum dd_frame_cadence_result dd_frame_cadence_observe(
        struct dd_frame_cadence *cadence, int64_t frame_time_ns, int64_t receipt_time_ns) {
    if (!cadence || frame_time_ns <= 0 || receipt_time_ns <= 0 ||
        frame_time_ns > receipt_time_ns)
        return DD_FRAME_CADENCE_REJECTED;

    if (!cadence->refresh_ns)
        cadence->refresh_ns = DD_FRAME_CADENCE_DEFAULT_NS;
    if (!cadence->last_frame_ns) {
        cadence->last_frame_ns = frame_time_ns;
        return DD_FRAME_CADENCE_BASELINED;
    }
    if (frame_time_ns <= cadence->last_frame_ns)
        return DD_FRAME_CADENCE_REJECTED;

    const int64_t delta_ns = frame_time_ns - cadence->last_frame_ns;
    if (delta_ns < DD_FRAME_CADENCE_MIN_NS)
        return DD_FRAME_CADENCE_REJECTED;

    cadence->last_frame_ns = frame_time_ns;
    if (delta_ns > DD_FRAME_CADENCE_MAX_NS) {
        cadence->candidate_ns = 0;
        cadence->candidate_samples = 0;
        return DD_FRAME_CADENCE_REBASED;
    }

    const int64_t current_distance =
            dd_frame_cadence_distance(delta_ns, cadence->refresh_ns);
    if (current_distance <= cadence->refresh_ns / 5) {
        cadence->refresh_ns += (delta_ns - cadence->refresh_ns) / 8;
        cadence->candidate_ns = 0;
        cadence->candidate_samples = 0;
        return DD_FRAME_CADENCE_UPDATED;
    }

    if (cadence->candidate_samples &&
        dd_frame_cadence_distance(delta_ns, cadence->candidate_ns) <=
                cadence->candidate_ns / 8) {
        cadence->candidate_ns += (delta_ns - cadence->candidate_ns) / 2;
        if (++cadence->candidate_samples >= 2) {
            cadence->refresh_ns = cadence->candidate_ns;
            cadence->candidate_ns = 0;
            cadence->candidate_samples = 0;
        }
    } else {
        cadence->candidate_ns = delta_ns;
        cadence->candidate_samples = 1;
    }
    return DD_FRAME_CADENCE_UPDATED;
}

#endif

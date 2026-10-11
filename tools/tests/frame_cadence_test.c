#include <assert.h>
#include <limits.h>
#include <stdint.h>

#include "frame_cadence.h"

static int64_t distance(int64_t a, int64_t b) {
    return a >= b ? a - b : b - a;
}

static void observe(struct dd_frame_cadence *cadence, int64_t frame_ns, int64_t delay_ns) {
    enum dd_frame_cadence_result result =
            dd_frame_cadence_observe(cadence, frame_ns, frame_ns + delay_ns);
    assert(result != DD_FRAME_CADENCE_REJECTED);
}

static void test_stable_rates_and_arrival_jitter(void) {
    struct dd_frame_cadence cadence = DD_FRAME_CADENCE_INITIALIZER;
    int64_t frame = 1000000000LL;
    const int64_t delays[] = { 100000LL, 7500000LL, 200000LL, 7000000LL, 300000LL };

    observe(&cadence, frame, delays[0]);
    for (int i = 1; i < 120; ++i) {
        frame += 16666667LL;
        observe(&cadence, frame, delays[i % 5]);
    }
    assert(distance(cadence.refresh_ns, 16666667LL) <= 1);

    for (int i = 0; i < 120; ++i) {
        frame += 8333333LL;
        observe(&cadence, frame, delays[(i + 2) % 5]);
    }
    assert(distance(cadence.refresh_ns, 8333333LL) <= 1);
}

static void test_invalid_timestamps_do_not_poison_history(void) {
    struct dd_frame_cadence cadence = DD_FRAME_CADENCE_INITIALIZER;
    const int64_t first = 2000000000LL;

    observe(&cadence, first, 1000000LL);
    assert(dd_frame_cadence_observe(&cadence, 0, first + 1) == DD_FRAME_CADENCE_REJECTED);
    assert(dd_frame_cadence_observe(&cadence, first + 20000000LL, first + 10000000LL) ==
           DD_FRAME_CADENCE_REJECTED); /* future */
    assert(dd_frame_cadence_observe(&cadence, first, first + 20000000LL) ==
           DD_FRAME_CADENCE_REJECTED); /* duplicate */
    assert(dd_frame_cadence_observe(&cadence, first - 1, first + 20000000LL) ==
           DD_FRAME_CADENCE_REJECTED); /* out of order */
    assert(dd_frame_cadence_observe(&cadence, first + DD_FRAME_CADENCE_MIN_NS - 1,
                                    first + 30000000LL) == DD_FRAME_CADENCE_REJECTED);
    assert(cadence.last_frame_ns == first);

    observe(&cadence, first + 16666667LL, 500000LL);
    assert(distance(cadence.refresh_ns, 16666667LL) <= 1);
}

static void test_gap_rebases_then_rate_switches_without_old_bias(void) {
    struct dd_frame_cadence cadence = DD_FRAME_CADENCE_INITIALIZER;
    int64_t frame = 3000000000LL;

    observe(&cadence, frame, 1000000LL);
    observe(&cadence, frame += 16666667LL, 1000000LL);
    frame += 2000000000LL;
    assert(dd_frame_cadence_observe(&cadence, frame, frame + 1000000LL) ==
           DD_FRAME_CADENCE_REBASED);
    assert(cadence.refresh_ns == 16666667LL);

    observe(&cadence, frame += 8333333LL, 30000000LL);
    assert(cadence.refresh_ns == 16666667LL); /* one sample cannot be a refresh switch */
    observe(&cadence, frame += 8333333LL, 100000LL);
    assert(distance(cadence.refresh_ns, 8333333LL) <= 1);
}

static void test_one_skipped_callback_does_not_change_rate(void) {
    struct dd_frame_cadence cadence = DD_FRAME_CADENCE_INITIALIZER;
    int64_t frame = 5000000000LL;

    observe(&cadence, frame, 1000000LL);
    observe(&cadence, frame += 16666667LL, 1000000LL);
    observe(&cadence, frame += 33333334LL, 1000000LL);
    assert(cadence.refresh_ns == 16666667LL);
    observe(&cadence, frame += 16666667LL, 1000000LL);
    assert(cadence.refresh_ns == 16666667LL);
}

static void test_bounds_and_large_values(void) {
    struct dd_frame_cadence cadence = DD_FRAME_CADENCE_INITIALIZER;
    int64_t frame = INT64_MAX - 100000000LL;

    observe(&cadence, frame, 1000000LL);
    observe(&cadence, frame += 16666667LL, 1000000LL);
    assert(cadence.refresh_ns >= DD_FRAME_CADENCE_MIN_NS);
    assert(cadence.refresh_ns <= DD_FRAME_CADENCE_MAX_NS);
    assert(dd_frame_cadence_observe(&cadence, INT64_MIN, INT64_MAX) ==
           DD_FRAME_CADENCE_REJECTED);

    assert(dd_frame_cadence_observe(&cadence, frame + DD_FRAME_CADENCE_MAX_NS + 1,
                                    INT64_MAX) == DD_FRAME_CADENCE_REBASED);
    assert(cadence.refresh_ns >= DD_FRAME_CADENCE_MIN_NS);
    assert(cadence.refresh_ns <= DD_FRAME_CADENCE_MAX_NS);
}

int main(void) {
    test_stable_rates_and_arrival_jitter();
    test_invalid_timestamps_do_not_poison_history();
    test_gap_rebases_then_rate_switches_without_old_bias();
    test_one_skipped_callback_does_not_change_rate();
    test_bounds_and_large_values();
    return 0;
}

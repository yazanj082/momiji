// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_CONGESTIONCONTROL_H
#define CHIAKI_CONGESTIONCONTROL_H

#include "takion.h"
#include "thread.h"
#include "packetstats.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct chiaki_congestion_control_t
{
	ChiakiTakion *takion;
	ChiakiPacketStats *stats;
	ChiakiThread thread;
	ChiakiBoolPredCond stop_cond;
	double packet_loss;
	double packet_loss_max;
	/**
	 * Packets received and lost since the start, as measured (before packet_loss_max), for statistics
	 * over any period. Read them with __atomic_load_n.
	 */
	uint64_t received_total;
	uint64_t lost_total;
} ChiakiCongestionControl;

CHIAKI_EXPORT ChiakiErrorCode chiaki_congestion_control_start(ChiakiCongestionControl *control, ChiakiTakion *takion, ChiakiPacketStats *stats, double packet_loss_max);

/**
 * Stop control and join the thread
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_congestion_control_stop(ChiakiCongestionControl *control);

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_CONGESTIONCONTROL_H

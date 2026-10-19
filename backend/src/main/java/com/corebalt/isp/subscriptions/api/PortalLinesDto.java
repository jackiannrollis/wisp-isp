package com.corebalt.isp.subscriptions.api;

import com.corebalt.isp.shared.money.MoneyDto;
import java.util.List;

/**
 * The portal's lines with the wallet balance and {@code payAll}: what the wallet lacks to renew every
 * due line at once, max(0, sum of their renewal prices - balance). The account reference of any payment
 * stays the customer number; the renewal job decides which line a payment renews.
 */
public record PortalLinesDto(List<PortalLineDto> lines, MoneyDto balance, MoneyDto payAll) {}

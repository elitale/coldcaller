package com.elitale.coldbirds.coldcalling.ui.controller;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.ui.support.DialTarget;

import javafx.scene.control.ListCell;

/**
 * List-cell renderers for the Power Dialer selector and "Up Next" preview. Kept out of
 * {@link PowerDialerController} so the controller stays focused on behaviour, not view plumbing.
 */
final class PowerDialerCells {

    private PowerDialerCells() {}

    /** Dial-target selector cell: "{All Leads | list name}  —  {summary}". */
    static ListCell<DialTarget> target() {
        return new ListCell<>() {
            @Override
            protected void updateItem(DialTarget item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : item.selectorLabel());
            }
        };
    }

    /** Upcoming-lead cell: "{name}  ·  {company}". */
    static ListCell<Lead> upNext() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Lead item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null
                        : item.displayName() + item.company().map(c -> "  ·  " + c).orElse(""));
            }
        };
    }
}

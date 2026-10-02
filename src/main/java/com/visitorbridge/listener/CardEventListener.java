package com.visitorbridge.listener;

import com.visitorbridge.client.NuveqEventDto;

public interface CardEventListener {

    void onCardEvent(NuveqEventDto event);
}

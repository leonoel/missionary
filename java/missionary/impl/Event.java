package missionary.impl;

import clojure.lang.AFn;
import clojure.lang.IFn;

final class Event extends AFn {
    interface Emitter {
        void cancel(Event e);
    }

    Emitter emitter;
    IFn success;
    IFn failure;

    Event(Emitter e, IFn s, IFn f) {
        emitter = e;
        success = s;
        failure = f;
    }

    @Override
    public Object invoke() {
        emitter.cancel(this);
        return null;
    }

}

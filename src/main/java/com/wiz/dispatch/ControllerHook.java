package com.wiz.dispatch;

import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

public interface ControllerHook {

    default WizResult before(WizContext wiz) {
        return null;
    }

    default WizResult after(WizContext wiz, WizResult result) {
        return result;
    }
}
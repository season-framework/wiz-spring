package com.wiz.dispatch;

import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;
import com.wiz.runtime.WizSegment;

public interface RouteHandler {

    String routeId();

    WizResult handle(WizContext context, WizSegment segment);
}
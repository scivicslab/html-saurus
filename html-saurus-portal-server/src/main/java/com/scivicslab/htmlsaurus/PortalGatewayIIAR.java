package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The actor {@value PortalNode#GATEWAY}: the one actor of the portal that other nodes may call, and only for the
 * actions of {@link PortalNode#ALLOWED}. Each action hands its JSON argument to {@link PortalGateway}.
 */
class PortalGatewayIIAR extends IIActorRef<PortalGateway> {

    PortalGatewayIIAR(String actorName, PortalGateway gateway, IIActorSystem system) {
        super(actorName, gateway, system);
    }

    @Action("search")
    public ActionResult search(String args) { return object.search(args); }

    @Action("searchSemantic")
    public ActionResult searchSemantic(String args) { return object.searchSemantic(args); }

    @Action("findRelated")
    public ActionResult findRelated(String args) { return object.findRelated(args); }

    @Action("references")
    public ActionResult references(String args) { return object.references(args); }

    @Action("readDocument")
    public ActionResult readDocument(String args) { return object.readDocument(args); }
}

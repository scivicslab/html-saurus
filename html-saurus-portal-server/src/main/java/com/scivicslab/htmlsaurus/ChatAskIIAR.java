package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/** The portal's {@value ChatAsk#CHAT_GATEWAY} gateway: where chat-ui sends the replies to the portal's requests. */
class ChatAskIIAR extends IIActorRef<ChatAsk> {

    ChatAskIIAR(String actorName, ChatAsk chatAsk, IIActorSystem system) {
        super(actorName, chatAsk, system);
    }

    @Action("enqueueReply")
    public ActionResult enqueueReply(String args) { return object.enqueueReply(args); }

    @Action("enqueueCancelResult")
    public ActionResult enqueueCancelResult(String args) { return object.enqueueCancelResult(args); }
}

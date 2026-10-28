package com.hrms.messaging;
public interface DomainEventHandler {boolean supports(String type);void handle(String id,String type,String aggregate);}

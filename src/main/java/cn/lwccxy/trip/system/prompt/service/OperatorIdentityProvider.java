package cn.lwccxy.trip.system.prompt.service;

/**
 * Supplies the authenticated operator for Prompt management writes.
 */
public interface OperatorIdentityProvider {

    String currentOperatorId();
}

package com.hrms.shared;
import java.util.function.Supplier;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;import org.springframework.transaction.support.TransactionTemplate;import org.springframework.transaction.PlatformTransactionManager;import org.springframework.dao.CannotAcquireLockException;import org.springframework.dao.PessimisticLockingFailureException;
@Component public class Tx {
 private final TransactionTemplate tx;private final TransactionTemplate nested;
 public Tx(PlatformTransactionManager manager) { tx=new TransactionTemplate(manager);tx.setIsolationLevel(2);tx.setTimeout(10);nested=new TransactionTemplate(manager);nested.setPropagationBehavior(6); }
 public <T> T nested(Supplier<T> work) {return nested.execute(s->work.get());}
 public <T> T run(Supplier<T> work) { if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())return work.get();for(int i=0;;i++) { try {return tx.execute(s->work.get());} catch(PessimisticLockingFailureException e) { if(i==2) throw e; try {Thread.sleep((1L<<i)*25+ThreadLocalRandom.current().nextLong(20));} catch(InterruptedException x) {Thread.currentThread().interrupt();throw new Problem(503,"INTERRUPTED");} } } }
}

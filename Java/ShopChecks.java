import java.time.*;
import java.util.*;
import java.util.function.*;

/** Focused executable checks of the Java port, no test framework required. */
public final class ShopChecks {
    static int count;
    static void ok(boolean value) { if(!value) throw new AssertionError(); }
    static void check(String name,Runnable test) {
        test.run(); count++; System.out.println("PASS "+name);
    }
    static void rejects(String code,Runnable work) {
        try { work.run(); throw new AssertionError("Expected "+code); }
        catch(ShopApp.PurchaseException ex) { ok(code.equals(ex.getMessage())); }
    }
    static ShopApp.PurchaseRequest one(String key,long offer,int q,long price) {
        return new ShopApp.PurchaseRequest(1,"COIN",key,List.of(new ShopApp.CartLine(offer,q,price)));
    }
    static void unchanged(ShopApp.InMemoryPurchaseStore store) {
        ShopApp.ShopState s=store.snapshot(); ok(s.wallets.get(1L).balance()==1000);
        ok(s.orders.isEmpty() && s.inventory.isEmpty() && s.ledger.isEmpty() && s.audit.isEmpty());
    }
    public static void main(String[] args) throws Exception {
        check("purchase_and_replay",()->{
            var store=new ShopApp.InMemoryPurchaseStore(ShopApp.sample()); var svc=ShopApp.service(store);
            var a=svc.purchase(ShopApp.request("a")); var b=svc.purchase(ShopApp.request("a"));
            var s=store.snapshot(); ok(a.id==b.id && a.total==510 && s.wallets.get(1L).balance()==490);
            ok(s.inventory.size()==2 && s.inventory.get(1).quantity==3 && s.ledger.size()==1 && s.audit.size()==1);
        });
        check("reordered_replay",()->{
            var store=new ShopApp.InMemoryPurchaseStore(ShopApp.sample()); var svc=ShopApp.service(store);
            svc.purchase(ShopApp.request("a"));
            ok(svc.purchase(new ShopApp.PurchaseRequest(1,"COIN","a",List.of(
                new ShopApp.CartLine(2,3,80),new ShopApp.CartLine(1,1,270)))).id==1);
        });
        check("key_conflict",()->{
            var svc=ShopApp.service(new ShopApp.InMemoryPurchaseStore(ShopApp.sample()));
            svc.purchase(ShopApp.request("a"));
            rejects("KEY_CONFLICT",()->svc.purchase(one("a",2,1,80)));
        });
        check("permanent_repeat",()->{
            var svc=ShopApp.service(new ShopApp.InMemoryPurchaseStore(ShopApp.sample()));
            svc.purchase(one("a",1,1,270)); rejects("ALREADY_OWNED",()->svc.purchase(one("b",1,1,270)));
        });
        check("consumable_repeat",()->{
            var store=new ShopApp.InMemoryPurchaseStore(ShopApp.sample()); var svc=ShopApp.service(store);
            svc.purchase(one("a",2,1,80)); svc.purchase(one("b",2,1,80));
            ok(store.snapshot().wallets.get(1L).balance()==840);
        });
        check("price_changed_no_effect",()->{
            var store=new ShopApp.InMemoryPurchaseStore(ShopApp.sample());
            rejects("PRICE_CHANGED",()->ShopApp.service(store).purchase(one("a",2,1,81))); unchanged(store);
        });
        check("insufficient_funds",()->{
            var store=new ShopApp.InMemoryPurchaseStore(ShopApp.sample());
            rejects("INSUFFICIENT_FUNDS",()->ShopApp.service(store).purchase(one("a",2,13,80))); unchanged(store);
        });
        check("exact_balance",()->{
            var state=ShopApp.sample(); state.wallets.put(1L,new ShopApp.Wallet(510,"COIN"));
            var store=new ShopApp.InMemoryPurchaseStore(state); ShopApp.service(store).purchase(ShopApp.request("a"));
            ok(store.snapshot().wallets.get(1L).balance()==0);
        });
        check("round_half_up",()->ok(new ShopApp.PercentagePricing().unitPrice(
            ShopApp.offer(3,new ShopApp.ConsumableItem(3,"x",0),101,50))==51));
        check("zero_price",()->rejects("ZERO_PRICE",()->new ShopApp.PercentagePricing().unitPrice(
            ShopApp.offer(3,new ShopApp.ConsumableItem(3,"x",0),100,100))));
        check("invalid_quantity",()->rejects("INVALID_LINE",()->new ShopApp.CartLine(1,0,100)));
        check("duplicate_offer",()->rejects("DUPLICATE_OFFER",()->new ShopApp.PurchaseRequest(1,"COIN","a",
            List.of(new ShopApp.CartLine(2,1,80),new ShopApp.CartLine(2,2,80)))));
        check("permanent_quantity",()->rejects("PERMANENT_QUANTITY",()->ShopApp.service(
            new ShopApp.InMemoryPurchaseStore(ShopApp.sample())).purchase(one("a",1,2,270))));
        check("age_restriction",()->{
            var s=ShopApp.sample(); s.players.put(1L,new ShopApp.Player(1,10,"RU",true));
            rejects("AGE_RESTRICTION",()->ShopApp.service(new ShopApp.InMemoryPurchaseStore(s)).purchase(ShopApp.request("a")));
        });
        check("region_restriction",()->{
            var s=ShopApp.sample(); s.players.put(1L,new ShopApp.Player(1,20,"EU",true));
            rejects("UNAVAILABLE_OFFER",()->ShopApp.service(new ShopApp.InMemoryPurchaseStore(s)).purchase(ShopApp.request("a")));
        });
        check("end_exclusive",()->{
            var s=ShopApp.sample(); var o=s.offers.get(1L);
            s.offers.put(1L,new ShopApp.CatalogOffer(1,o.item,300,10,"RU","COIN",true,ShopApp.NOW.minusSeconds(1),ShopApp.NOW));
            rejects("UNAVAILABLE_OFFER",()->ShopApp.service(new ShopApp.InMemoryPurchaseStore(s)).purchase(ShopApp.request("a")));
        });
        check("transaction_rollback_after_writes",()->{
            var store=new ShopApp.InMemoryPurchaseStore(ShopApp.sample());
            ShopApp.PurchaseStore failure=new ShopApp.PurchaseStore() {
                public <T>T inTransaction(Function<ShopApp.ShopState,T> work) {
                    return store.inTransaction(s->{work.apply(s); throw new ShopApp.PurchaseException("INJECTED");});
                }
            };
            rejects("INJECTED",()->ShopApp.service(failure).purchase(ShopApp.request("a"))); unchanged(store);
        });
        check("duplicate_item",()->{
            var s=ShopApp.sample(); s.offers.put(3L,ShopApp.offer(3,s.offers.get(2L).item,100,20));
            rejects("DUPLICATE_ITEM",()->ShopApp.service(new ShopApp.InMemoryPurchaseStore(s)).purchase(
                new ShopApp.PurchaseRequest(1,"COIN","a",List.of(new ShopApp.CartLine(2,1,80),new ShopApp.CartLine(3,1,80)))));
        });
        // Two callers share the same store and key; both must receive a single order.
        var store=new ShopApp.InMemoryPurchaseStore(ShopApp.sample()); var svc=ShopApp.service(store);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->svc.purchase(ShopApp.request("parallel")));
            var b=pool.submit(()->svc.purchase(ShopApp.request("parallel")));
            ok(a.get().id==b.get().id); ok(store.snapshot().ledger.size()==1);
            count++; System.out.println("PASS concurrent_replay");
        } finally { pool.shutdownNow(); }
        System.out.println("Passed: "+count);
    }
}

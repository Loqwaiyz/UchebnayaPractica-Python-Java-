import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Stage 4: dependency-free object-oriented purchase kernel, Java 11+. */
public final class ShopApp {
    static void require(boolean condition, String code) {
        if (!condition) throw new PurchaseException(code);
    }
    public static final class PurchaseException extends RuntimeException {
        PurchaseException(String code) { super(code); }
    }
    public abstract static class Item {
        private final long id;
        private final String title;
        private final int minAge;
        protected Item(long id, String title, int minAge) {
            require(id > 0 && title != null && !title.isBlank()
                    && minAge >= 0 && minAge <= 120, "INVALID_ITEM");
            this.id = id; this.title = title; this.minAge = minAge;
        }
        public long id() { return id; }
        public String title() { return title; }
        public int minAge() { return minAge; }
        public abstract void validatePurchase(int quantity, boolean owned);
    }
    public static final class PermanentItem extends Item {
        public PermanentItem(long id, String title, int age) { super(id, title, age); }
        @Override public void validatePurchase(int quantity, boolean owned) {
            require(quantity == 1, "PERMANENT_QUANTITY");
            require(!owned, "ALREADY_OWNED");
        }
    }
    public static final class ConsumableItem extends Item {
        public ConsumableItem(long id, String title, int age) { super(id, title, age); }
        @Override public void validatePurchase(int quantity, boolean owned) {
            require(quantity >= 1 && quantity <= 999, "INVALID_QUANTITY");
        }
    }
    public static final class Player {
        final long id; final int age; final String region; final boolean active;
        public Player(long id, int age, String region, boolean active) {
            require(id > 0 && age >= 0 && age <= 120 && region != null
                    && !region.isBlank(), "INVALID_PLAYER");
            this.id=id; this.age=age; this.region=region; this.active=active;
        }
    }
    public static final class Wallet {
        private long balance;
        final String currency;
        public Wallet(long balance, String currency) {
            require(balance >= 0 && "COIN".equals(currency), "INVALID_WALLET");
            this.balance=balance; this.currency=currency;
        }
        public long balance() { return balance; }
        void debit(long amount) {
            require(amount > 0 && amount <= balance, "INSUFFICIENT_FUNDS");
            balance -= amount;
        }
    }
    public static final class CatalogOffer {
        final long id; final Item item; final long basePrice; final int discount;
        final String region, currency; final boolean active;
        final Instant from, to;
        public CatalogOffer(long id, Item item, long price, int discount,
                String region, String currency, boolean active, Instant from, Instant to) {
            require(id > 0 && item != null && price >= 1 && price <= 1_000_000_000L
                    && discount >= 0 && discount <= 100 && region != null
                    && !region.isBlank() && "COIN".equals(currency)
                    && from != null && to != null && from.isBefore(to), "INVALID_OFFER");
            this.id=id; this.item=item; this.basePrice=price; this.discount=discount;
            this.region=region; this.currency=currency; this.active=active;
            this.from=from; this.to=to;
        }
        void validate(Player player, Instant now) {
            require(active && region.equals(player.region) && !now.isBefore(from)
                    && now.isBefore(to), "UNAVAILABLE_OFFER");
            require(player.age >= item.minAge(), "AGE_RESTRICTION");
        }
    }
    public interface PricingPolicy { long unitPrice(CatalogOffer offer); }
    public static final class PercentagePricing implements PricingPolicy {
        @Override public long unitPrice(CatalogOffer offer) {
            long price=(offer.basePrice * (100L-offer.discount) + 50L) / 100L;
            require(price > 0, "ZERO_PRICE"); return price;
        }
    }
    public static final class CartLine {
        final long offerId, expectedPrice; final int quantity;
        public CartLine(long offerId, int quantity, long price) {
            require(offerId > 0 && quantity >= 1 && quantity <= 999
                    && price >= 1 && price <= 1_000_000_000L, "INVALID_LINE");
            this.offerId=offerId; this.quantity=quantity; this.expectedPrice=price;
        }
        @Override public boolean equals(Object obj) {
            if (!(obj instanceof CartLine)) return false;
            CartLine x=(CartLine)obj;
            return offerId==x.offerId && quantity==x.quantity && expectedPrice==x.expectedPrice;
        }
        @Override public int hashCode() { return Objects.hash(offerId,quantity,expectedPrice); }
    }
    public static final class PurchaseRequest {
        final long playerId; final String currency, key; final List<CartLine> lines;
        public PurchaseRequest(long playerId, String currency, String key, List<CartLine> lines) {
            require(playerId > 0 && "COIN".equals(currency) && key != null
                    && !key.isBlank() && key.length() <= 128 && lines != null
                    && !lines.isEmpty() && lines.size() <= 100, "INVALID_REQUEST");
            ArrayList<CartLine> copy=new ArrayList<>(lines);
            require(!copy.contains(null), "INVALID_LINE");
            copy.sort(Comparator.comparingLong(x -> x.offerId));
            for (int i=1;i<copy.size();i++)
                require(copy.get(i-1).offerId != copy.get(i).offerId, "DUPLICATE_OFFER");
            this.playerId=playerId; this.currency=currency; this.key=key;
            this.lines=List.copyOf(copy);
        }
        boolean samePayload(PurchaseRequest other) {
            return playerId==other.playerId && currency.equals(other.currency)
                    && lines.equals(other.lines);
        }
    }
    public static final class OrderLine {
        public final long offerId, itemId, basePrice, unitPrice, total;
        public final int quantity, discount;
        OrderLine(CatalogOffer offer, CartLine line, long unitPrice) {
            offerId=offer.id; itemId=offer.item.id(); quantity=line.quantity;
            basePrice=offer.basePrice; discount=offer.discount;
            this.unitPrice=unitPrice; total=Math.multiplyExact(unitPrice,quantity);
        }
    }
    public static final class Order {
        public final long id, total; public final List<OrderLine> lines;
        final PurchaseRequest request;
        Order(long id, PurchaseRequest request, List<OrderLine> lines, long total) {
            this.id=id; this.request=request; this.lines=List.copyOf(lines); this.total=total;
        }
    }
    public static final class InventoryEntry {
        final long orderId, playerId, itemId; final int position, quantity;
        InventoryEntry(long orderId,long playerId,OrderLine line,int position) {
            this.orderId=orderId; this.playerId=playerId; this.itemId=line.itemId;
            this.position=position; this.quantity=line.quantity;
        }
    }
    public static final class LedgerEntry {
        final long orderId, amount;
        LedgerEntry(long orderId,long amount) { this.orderId=orderId; this.amount=amount; }
    }
    public static final class AuditEvent {
        final long orderId; final Instant at;
        final String type="purchase_completed";
        AuditEvent(long orderId, Instant at) { this.orderId=orderId; this.at=at; }
    }
    // Mutable aggregate state is package-private, never returned to callers of the service.
    static final class ShopState {
        final Map<Long,Player> players=new HashMap<>();
        final Map<Long,Wallet> wallets=new HashMap<>();
        final Map<Long,CatalogOffer> offers=new HashMap<>();
        final Map<String,Order> orders=new HashMap<>();
        final List<InventoryEntry> inventory=new ArrayList<>();
        final List<LedgerEntry> ledger=new ArrayList<>();
        final List<AuditEvent> audit=new ArrayList<>();
        long nextOrderId=1;
        ShopState copy() {
            ShopState s=new ShopState(); s.players.putAll(players); s.offers.putAll(offers);
            wallets.forEach((id,w)->s.wallets.put(id,new Wallet(w.balance(),w.currency)));
            s.orders.putAll(orders); s.inventory.addAll(inventory);
            s.ledger.addAll(ledger); s.audit.addAll(audit); s.nextOrderId=nextOrderId;
            return s;
        }
    }
    public interface PurchaseStore {
        // A callback failure must discard all changes; execution must be serialized.
        <T> T inTransaction(Function<ShopState,T> work);
    }
    public static final class InMemoryPurchaseStore implements PurchaseStore {
        private ShopState committed;
        InMemoryPurchaseStore(ShopState initial) { committed=initial.copy(); }
        @Override public synchronized <T> T inTransaction(Function<ShopState,T> work) {
            ShopState draft=committed.copy();
            T result=work.apply(draft); committed=draft; return result;
        }
        synchronized ShopState snapshot() { return committed.copy(); }
    }
    public static final class PurchaseService {
        private final PurchaseStore store; private final PricingPolicy pricing;
        private final Clock clock;
        public PurchaseService(PurchaseStore store, PricingPolicy pricing, Clock clock) {
            this.store=Objects.requireNonNull(store); this.pricing=Objects.requireNonNull(pricing);
            this.clock=Objects.requireNonNull(clock);
        }
        public Order purchase(PurchaseRequest request) {
            Objects.requireNonNull(request);
            return store.inTransaction(s -> {
                Order old=s.orders.get(request.key);
                if (old!=null) {
                    require(old.request.samePayload(request), "KEY_CONFLICT"); return old;
                }
                Player player=s.players.get(request.playerId);
                require(player!=null && player.active, "INVALID_PLAYER");
                Wallet wallet=s.wallets.get(player.id);
                require(wallet!=null && wallet.currency.equals(request.currency), "INVALID_WALLET");
                Set<Long> owned=new HashSet<>();
                for (InventoryEntry e:s.inventory) if(e.playerId==player.id) owned.add(e.itemId);
                Set<Long> seen=new HashSet<>(); List<OrderLine> priced=new ArrayList<>();
                long total=0; Instant now=clock.instant();
                for (CartLine line:request.lines) {
                    CatalogOffer offer=s.offers.get(line.offerId);
                    require(offer!=null, "OFFER_NOT_FOUND"); offer.validate(player,now);
                    require(seen.add(offer.item.id()), "DUPLICATE_ITEM");
                    offer.item.validatePurchase(line.quantity,owned.contains(offer.item.id()));
                    long unit=pricing.unitPrice(offer);
                    require(unit==line.expectedPrice, "PRICE_CHANGED");
                    OrderLine result=new OrderLine(offer,line,unit); priced.add(result);
                    total=Math.addExact(total,result.total);
                }
                wallet.debit(total);
                Order order=new Order(s.nextOrderId++,request,priced,total);
                s.orders.put(request.key,order);
                for(int i=0;i<priced.size();i++)
                    s.inventory.add(new InventoryEntry(order.id,player.id,priced.get(i),i+1));
                s.ledger.add(new LedgerEntry(order.id,-total));
                s.audit.add(new AuditEvent(order.id,now)); return order;
            });
        }
    }
    static final Instant NOW=Instant.parse("2026-11-19T09:00:00Z");
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static ShopState sample() {
        ShopState s=new ShopState(); s.players.put(1L,new Player(1,20,"RU",true));
        s.wallets.put(1L,new Wallet(1000,"COIN"));
        s.offers.put(1L,offer(1,new PermanentItem(1,"Cosmetic sword",12),300,10));
        s.offers.put(2L,offer(2,new ConsumableItem(2,"Health potion",0),100,20));
        return s;
    }
    static CatalogOffer offer(long id,Item item,long price,int discount) {
        return new CatalogOffer(id,item,price,discount,"RU","COIN",true,
                NOW.minusSeconds(3600),NOW.plusSeconds(3600));
    }
    static PurchaseRequest request(String key) {
        return new PurchaseRequest(1,"COIN",key,
                List.of(new CartLine(1,1,270),new CartLine(2,3,80)));
    }
    static PurchaseService service(PurchaseStore store) {
        return new PurchaseService(store,new PercentagePricing(),CLOCK);
    }
    public static void main(String[] args) {
        InMemoryPurchaseStore store=new InMemoryPurchaseStore(sample());
        PurchaseService service=service(store); Order order=service.purchase(request("demo-001"));
        Order repeated=service.purchase(request("demo-001")); ShopState s=store.snapshot();
        System.out.println("total="+order.total+"; balance="+s.wallets.get(1L).balance()
                +"; order="+order.id+"; repeat="+repeated.id+"; orders="+s.orders.size()
                +"; inventory="+s.inventory.size()+"; ledger="+s.ledger.size()+"; audit="+s.audit.size());
        for(OrderLine line:order.lines)
            System.out.println("item="+line.itemId+"; quantity="+line.quantity+"; unit="+line.unitPrice);
    }
}

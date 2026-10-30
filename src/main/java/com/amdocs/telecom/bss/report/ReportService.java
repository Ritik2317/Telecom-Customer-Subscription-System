package com.amdocs.telecom.bss.report;

import com.amdocs.telecom.bss.dao.BssStore;
import com.amdocs.telecom.bss.dao.BssStore.Row;
import com.amdocs.telecom.bss.model.Domain.Session;
import com.amdocs.telecom.bss.service.AuthenticationService;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.YearMonth;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import static com.amdocs.telecom.bss.validation.Rules.*;

/** Stream-based analyses over real persisted rows. Money is always aggregated as BigDecimal. */
public final class ReportService {
    public record Table(String name,List<String> headers,List<List<String>> rows) { }
    public interface Writer {
        String render(Table table);
        default String cell(String input) { return input.replace("\r"," ").replace("\n"," ").replace("\t"," "); }
    }
    public enum Format {
        CSV { public Writer writer() { return t->{StringBuilder b=new StringBuilder();
            Consumer<List<String>> line=cells->b.append(cells.stream().map(ReportService::csv).collect(Collectors.joining(","))).append('\n');
            line.accept(t.headers());t.rows().forEach(line);return b.toString();}; } },
        TEXT { public Writer writer() { return t->{StringBuilder b=new StringBuilder(t.name()+"\n"+String.join(" | ",t.headers())+"\n");
            t.rows().forEach(r->b.append(r.stream().map(v->v.replace("\n"," ").replace("\r"," ")).collect(Collectors.joining(" | "))).append('\n'));return b.toString();}; } };
        public abstract Writer writer();
    }
    private final BssStore store;private final AuthenticationService auth;
    public ReportService(BssStore store,AuthenticationService auth) {this.store=store;this.auth=auth;}
    private static String csv(String value) {
        String v=value.replace("\r"," ").replace("\n"," ");
        if(v.matches("^[=+@-].*")) v="'"+v;
        return "\""+v.replace("\"","\"\"")+"\"";
    }
    private static Table grouped(String name,String keyLabel,String valueLabel,Map<String,?> map) {
        return new Table(name,Arrays.asList(keyLabel,valueLabel),map.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e->Arrays.asList(e.getKey(),String.valueOf(e.getValue()))).collect(Collectors.toList()));
    }
    public List<Table> generate(Session s,YearMonth month,BigDecimal minimumPrice,BigDecimal maximumPrice) {
        nonnegative(minimumPrice,"Minimum price");nonnegative(maximumPrice,"Maximum price");require(minimumPrice.compareTo(maximumPrice)<=0,"Invalid price range");
        return store.transaction(tx->{auth.admin(tx,s);
            List<Row> customers=tx.query("SELECT * FROM bss_customers"),plans=tx.query("SELECT * FROM bss_plans"),subs=tx.query("SELECT * FROM bss_subscriptions");
            List<Row> usage=tx.query("SELECT u.*,s.customer_id,c.customer_number FROM bss_usage u JOIN bss_subscriptions s ON s.id=u.subscription_id JOIN bss_customers c ON c.id=s.customer_id WHERE u.usage_date>=? AND u.usage_date<? AND u.status='PROCESSED'",month.atDay(1).atStartOfDay(),month.plusMonths(1).atDay(1).atStartOfDay());
            List<Row> payments=tx.query("SELECT p.* FROM bss_payments p WHERE payment_date>=? AND payment_date<?",month.atDay(1).atStartOfDay(),month.plusMonths(1).atDay(1).atStartOfDay());
            List<Row> unpaid=tx.query("SELECT b.*,s.customer_id,c.customer_number FROM bss_bills b JOIN bss_subscriptions s ON s.id=b.subscription_id JOIN bss_customers c ON c.id=s.customer_id WHERE b.status='UNPAID'");
            Supplier<List<Table>> factory=ArrayList::new;List<Table> result=factory.get();
            Map<String,BigDecimal> leaders=usage.stream().collect(Collectors.groupingBy(u->u.text("usage_type")+"/"+u.text("customer_number"),Collectors.reducing(BigDecimal.ZERO,u->u.decimal("quantity"),BigDecimal::add)));
            result.add(new Table("highest-usage-customers",Arrays.asList("Type/customer","Quantity (GB, minutes, or count by type)"),leaders.entrySet().stream().sorted(Map.Entry.<String,BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).map(e->Arrays.asList(e.getKey(),e.getValue().toPlainString())).collect(Collectors.toList())));
            result.add(grouped("customers-by-city","City","Customers",customers.stream().collect(Collectors.groupingBy(c->c.text("city"),Collectors.counting()))));
            Predicate<Row> within=p->p.flag("active") && p.decimal("monthly_rental").compareTo(minimumPrice)>=0 && p.decimal("monthly_rental").compareTo(maximumPrice)<=0;
            result.add(new Table("plans-in-price-range",Arrays.asList("Code","Name","Rental"),plans.stream().filter(within).sorted(Comparator.comparing(p->p.decimal("monthly_rental"))).map(p->Arrays.asList(p.text("plan_code"),p.text("plan_name"),p.text("monthly_rental"))).collect(Collectors.toList())));
            Function<Row,String> planCode=row->plans.stream().filter(p->p.id("id")==row.id("plan_id")).map(p->p.text("plan_code")).findFirst().orElse("Unknown");
            Map<String,Long> popular=subs.stream().collect(Collectors.groupingBy(planCode,Collectors.counting()));
            result.add(new Table("most-subscribed-plans",Arrays.asList("Plan","Connections"),popular.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).map(e->Arrays.asList(e.getKey(),e.getValue().toString())).collect(Collectors.toList())));
            BigDecimal revenue=payments.stream().map(p->p.decimal("amount")).reduce(BigDecimal.ZERO,BigDecimal::add);
            result.add(new Table("monthly-revenue",Arrays.asList("Month","Confirmed payments"),List.of(List.of(month.toString(),money(revenue).toPlainString()))));
            Map<String,DoubleSummaryStatistics> stats=usage.stream().collect(Collectors.groupingBy(u->u.text("usage_type")+" ("+u.text("unit")+")",Collectors.summarizingDouble(u->u.decimal("quantity").doubleValue())));
            result.add(new Table("usage-by-type",Arrays.asList("Month/type/unit","Total","Events","Average"),stats.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->Arrays.asList(month+"/"+e.getKey(),Double.toString(e.getValue().getSum()),Long.toString(e.getValue().getCount()),Double.toString(e.getValue().getAverage()))).collect(Collectors.toList())));
            result.add(grouped("customers-with-unpaid-bills","Customer","Outstanding",unpaid.stream().collect(Collectors.groupingBy(b->b.text("customer_number"),Collectors.reducing(BigDecimal.ZERO,b->b.decimal("total_amount"),BigDecimal::add)))));
            long eligible=customers.stream().filter(c->!YearMonth.from(c.time("created_at")).isAfter(month)).count();
            BigDecimal average=eligible==0?BigDecimal.ZERO:revenue.divide(BigDecimal.valueOf(eligible),2,RoundingMode.HALF_UP);
            result.add(new Table("average-monthly-revenue-per-customer",Arrays.asList("Month","Revenue","Registered customers by month end","ARPU"),List.of(List.of(month.toString(),money(revenue).toPlainString(),Long.toString(eligible),money(average).toPlainString()))));
            return result;
        });
    }
    public List<Path> export(Session s,YearMonth month,Path directory,BigDecimal minimumPrice,BigDecimal maximumPrice) {
        List<Table> reports=generate(s,month,minimumPrice,maximumPrice);List<Path> files=new ArrayList<>();
        try {
            Files.createDirectories(directory);
            for(Table table:reports) for(Format format:Format.values()) {
                Path file=directory.resolve(month+"-"+table.name()+"."+(format==Format.CSV?"csv":"txt"));
                Files.writeString(file,format.writer().render(table),StandardCharsets.UTF_8);files.add(file.toAbsolutePath());
            }
        } catch(java.io.IOException e) {throw new IllegalStateException("Report export failed; check directory permissions",e);}
        return files;
    }
}

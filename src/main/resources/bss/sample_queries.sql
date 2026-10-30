-- BSS SQL reference. Read-only examples; never run on an unknown database.
-- JOIN: customer identity and account status.
SELECT c.customer_number,c.city,u.username,u.status
FROM bss_customers c JOIN bss_users u ON u.id=c.user_id ORDER BY c.customer_number;

-- LEFT JOIN, GROUP BY, aggregates: preserve plans with no subscribers.
SELECT p.plan_code,COUNT(s.id) AS connections,MIN(p.monthly_rental) AS rental
FROM bss_plans p LEFT JOIN bss_subscriptions s ON s.plan_id=p.id
GROUP BY p.plan_code ORDER BY connections DESC,p.plan_code;

-- HAVING, CASE: customers with unpaid balances.
SELECT c.customer_number,SUM(CASE WHEN b.status='UNPAID' THEN b.total_amount ELSE 0 END) AS outstanding
FROM bss_customers c JOIN bss_subscriptions s ON s.customer_id=c.id
LEFT JOIN bss_bills b ON b.subscription_id=s.id
GROUP BY c.customer_number
HAVING SUM(CASE WHEN b.status='UNPAID' THEN b.total_amount ELSE 0 END)>0
ORDER BY outstanding DESC;

-- SUBQUERY: customers with multiple connections.
SELECT customer_number FROM bss_customers
WHERE id IN (SELECT customer_id FROM bss_subscriptions GROUP BY customer_id HAVING COUNT(*)>1);

-- Date functions, aggregate functions, GROUP BY and ORDER BY: monthly receipts.
SELECT YEAR(payment_date) AS receipt_year,MONTH(payment_date) AS receipt_month,
SUM(amount) AS revenue,AVG(amount) AS average_payment,MIN(amount) AS smallest,MAX(amount) AS largest
FROM bss_payments GROUP BY YEAR(payment_date),MONTH(payment_date)
ORDER BY receipt_year,receipt_month;

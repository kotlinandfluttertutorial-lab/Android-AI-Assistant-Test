import json
with open('coverage_fresh.json') as f:
    data = json.load(f)

files = data['files']
total = data.get('totals', {})
print('=== TOTALS ===')
print(json.dumps(total, indent=2))

print()
print('=== FILES WITH 0% COVERAGE (sorted by num_statements desc) ===')
zero = [(k, v['summary']) for k, v in files.items() if v['summary']['percent_covered'] == 0.0 and v['summary']['num_statements'] > 0]
zero.sort(key=lambda x: -x[1]['num_statements'])
for fname, s in zero:
    print(f"{s['num_statements']:4d} stmts  {fname}")

print()
print('=== FILES WITH LOW COVERAGE (1-69%) ===')
low = [(k, v['summary']) for k, v in files.items() if 0 < v['summary']['percent_covered'] < 70 and v['summary']['num_statements'] > 0]
low.sort(key=lambda x: -x[1]['num_statements'])
for fname, s in low:
    print(f"{s['percent_covered_display']:>4}%  {s['num_statements']:4d} stmts  {fname}")

print()
print('=== COVERAGE MATH ===')
covered = total.get('covered_lines', 0)
num_stmts = total.get('num_statements', 0)
missing = total.get('missing_lines', 0)
pct = total.get('percent_covered', 0)
print(f"Total statements: {num_stmts}")
print(f"Covered: {covered}")
print(f"Missing: {missing}")
print(f"Current: {pct:.2f}%")
# lines needed to reach 70%
needed_covered = int(0.70 * num_stmts)
lines_to_add = needed_covered - covered
print(f"Lines needed to reach 70%: {lines_to_add}")

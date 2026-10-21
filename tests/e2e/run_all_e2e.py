#!/usr/bin/env python3
"""
Unified E2E Test Suite Runner for DLivePTApp
Executes all 4 systematic tiers and outputs structured status reports with standard exit codes.
"""

import sys
import os
import time
import argparse
import unittest
from io import StringIO

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

TIER_DIRECTORIES = {
    "Tier 1: Feature Coverage": os.path.join(PROJECT_ROOT, "tests", "e2e", "tier1_feature_coverage"),
    "Tier 2: Boundary & Corner Cases": os.path.join(PROJECT_ROOT, "tests", "e2e", "tier2_boundary_corner"),
    "Tier 3: Cross-Feature Combinations": os.path.join(PROJECT_ROOT, "tests", "e2e", "tier3_cross_combinations"),
    "Tier 4: Real-World Scenarios": os.path.join(PROJECT_ROOT, "tests", "e2e", "tier4_realworld_scenarios")
}

def parse_args():
    parser = argparse.ArgumentParser(description="Run DLivePTApp E2E Test Suite")
    parser.add_argument("--tier", choices=["1", "2", "3", "4", "all"], default="all",
                        help="Select tier to execute (default: all)")
    parser.add_argument("-v", "--verbose", action="store_true", default=False,
                        help="Verbose unittest output")
    return parser.parse_args()

def run_suite():
    args = parse_args()
    
    print("=" * 78)
    print("           DLivePTApp - End-to-End (E2E) Test Suite Runner            ")
    print("=" * 78)
    print(f"Project Root: {PROJECT_ROOT}")
    print(f"Execution Tier Scope: {args.tier.upper()}")
    print("-" * 78)

    loader = unittest.TestLoader()
    total_tests = 0
    total_passes = 0
    total_failures = 0
    total_errors = 0
    overall_start_time = time.time()
    tier_results = []

    selected_tiers = {}
    if args.tier == "all":
        selected_tiers = TIER_DIRECTORIES
    else:
        tier_key = f"Tier {args.tier}"
        for k, v in TIER_DIRECTORIES.items():
            if k.startswith(tier_key):
                selected_tiers[k] = v

    for tier_name, tier_dir in selected_tiers.items():
        print(f"\n[TIER] Running {tier_name}...")
        t0 = time.time()
        suite = loader.discover(tier_dir, pattern="test_*.py")
        count = suite.countTestCases()
        
        verbosity = 2 if args.verbose else 1
        runner = unittest.TextTestRunner(verbosity=verbosity)
        result = runner.run(suite)
        elapsed = time.time() - t0

        passes = count - len(result.failures) - len(result.errors)
        total_tests += count
        total_passes += passes
        total_failures += len(result.failures)
        total_errors += len(result.errors)

        tier_status = "PASSED" if (len(result.failures) == 0 and len(result.errors) == 0) else "FAILED"
        tier_results.append({
            "name": tier_name,
            "count": count,
            "passes": passes,
            "failures": len(result.failures),
            "errors": len(result.errors),
            "time": elapsed,
            "status": tier_status
        })

    total_elapsed = time.time() - overall_start_time

    # Print Summary Matrix
    print("\n" + "=" * 78)
    print("                            TEST RESULTS SUMMARY                           ")
    print("=" * 78)
    print(f"{'Tier Name':<38} | {'Total':<6} | {'Pass':<6} | {'Fail':<6} | {'Time (s)':<8} | {'Status':<6}")
    print("-" * 78)
    for r in tier_results:
        print(f"{r['name']:<38} | {r['count']:<6} | {r['passes']:<6} | {r['failures']:<6} | {r['time']:<8.2f} | {r['status']:<6}")
    print("-" * 78)
    print(f"{'OVERALL TOTALS':<38} | {total_tests:<6} | {total_passes:<6} | {total_failures + total_errors:<6} | {total_elapsed:<8.2f} | {'PASSED' if total_failures + total_errors == 0 else 'FAILED':<6}")
    print("=" * 78)

    if total_failures + total_errors == 0:
        print("\n[SUCCESS] All E2E test assertions passed cleanly.")
        return 0
    else:
        print(f"\n[FAILURE] {total_failures} test(s) failed, {total_errors} error(s) occurred.")
        return 1

if __name__ == "__main__":
    sys.exit(run_suite())

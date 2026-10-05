'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth/auth-context';
import { Button } from '@/components/ui/button';
import { Input, PasswordInput } from '@/components/ui/input';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { StatusDot } from '@/components/ui/badge';
import { ArrowRight, Server, ShieldCheck } from 'lucide-react';

export default function LoginPage() {
  const router = useRouter();
  const { login, serverInfo } = useAuth();
  const [email, setEmail] = useState('theanh@landrop.io');
  const [password, setPassword] = useState('password123');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setError('');

    try {
      const success = await login(email, password);
      if (success) {
        router.push('/workspaces');
      } else {
        setError('Invalid email or password');
      }
    } catch {
      setError('Authentication failed. Please verify credentials.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col justify-center items-center p-4">
      {/* Brand Header */}
      <div className="flex items-center gap-3 mb-8">
        <div className="w-10 h-10 rounded-xl bg-indigo-600 flex items-center justify-center font-bold text-white text-base shadow-sm">
          L
        </div>
        <div>
          <div className="text-lg font-bold text-slate-900 tracking-tight">LANDrop</div>
          <div className="text-xs text-slate-500 font-medium">Browser Collaborative Workspace</div>
        </div>
      </div>

      <Card className="w-full max-w-md shadow-card">
        <CardHeader>
          {/* Connected Server Indicator */}
          <div className="flex items-center justify-between p-2.5 bg-slate-50 border border-slate-200/80 rounded-lg text-xs mb-3">
            <span className="flex items-center gap-2 text-slate-600">
              <Server className="w-3.5 h-3.5 text-slate-400" />
              <span>Connected Server:</span>
              <strong className="text-slate-900 font-medium">{serverInfo.serverName}</strong>
            </span>
            <Link
              href="/connect"
              className="text-[11px] text-indigo-600 hover:text-indigo-800 font-medium"
            >
              Change
            </Link>
          </div>

          <CardTitle>Sign in to your account</CardTitle>
          <CardDescription>
            Enter your email and credentials to enter your team workspaces
          </CardDescription>
        </CardHeader>

        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-4">
            {error && (
              <div className="p-3 bg-red-50 border border-red-200 text-red-700 text-xs rounded-lg font-medium">
                {error}
              </div>
            )}

            <Input
              label="Email Address"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@company.com"
              required
            />

            <div>
              <div className="flex items-center justify-between mb-1.5">
                <label className="text-xs font-medium text-slate-700">Password</label>
                <a href="#" className="text-[11px] text-indigo-600 hover:underline">
                  Forgot password?
                </a>
              </div>
              <PasswordInput
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
                required
              />
            </div>

            <Button
              type="submit"
              variant="primary"
              size="md"
              className="w-full mt-2"
              isLoading={isLoading}
              rightIcon={<ArrowRight className="w-4 h-4" />}
            >
              Sign In
            </Button>
          </form>

          <div className="mt-5 text-center text-xs text-slate-500">
            Don&apos;t have an account?{' '}
            <Link href="/auth/register" className="text-indigo-600 font-medium hover:underline">
              Create account
            </Link>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

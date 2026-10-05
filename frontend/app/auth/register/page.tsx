'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth/auth-context';
import { Button } from '@/components/ui/button';
import { Input, PasswordInput } from '@/components/ui/input';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { Server, ArrowRight } from 'lucide-react';

export default function RegisterPage() {
  const router = useRouter();
  const { register, serverInfo } = useAuth();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setError('');

    try {
      const success = await register(name, email, password);
      if (success) {
        router.push('/workspaces');
      } else {
        setError('Failed to create account.');
      }
    } catch {
      setError('Registration error.');
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
          <div className="flex items-center justify-between p-2.5 bg-slate-50 border border-slate-200/80 rounded-lg text-xs mb-3">
            <span className="flex items-center gap-2 text-slate-600">
              <Server className="w-3.5 h-3.5 text-slate-400" />
              <span>Target Server:</span>
              <strong className="text-slate-900 font-medium">{serverInfo.serverName}</strong>
            </span>
          </div>

          <CardTitle>Create an account</CardTitle>
          <CardDescription>
            Join your organization&apos;s LANDrop server and start collaborating
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
              label="Full Name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="The Anh"
              required
            />

            <Input
              label="Email Address"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@company.com"
              required
            />

            <PasswordInput
              label="Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="At least 8 characters"
              required
            />

            <Button
              type="submit"
              variant="primary"
              size="md"
              className="w-full mt-2"
              isLoading={isLoading}
              rightIcon={<ArrowRight className="w-4 h-4" />}
            >
              Register & Enroll Browser
            </Button>
          </form>

          <div className="mt-5 text-center text-xs text-slate-500">
            Already have an account?{' '}
            <Link href="/auth/login" className="text-indigo-600 font-medium hover:underline">
              Sign In
            </Link>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
